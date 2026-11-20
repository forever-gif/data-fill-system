# -*- coding: utf-8 -*-
"""
FastAPI Server for CPT Generator Workbench
Completely standalone on port 8088 (separate from 8080/5173).
"""

import os
import re
import glob
from typing import List, Optional
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.responses import HTMLResponse, FileResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from cpt_engine import CptModel, col_idx_to_name, col_name_to_idx

app = FastAPI(title="FineReport CPT Intelligent Workbench")

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
STATIC_DIR = os.path.join(BASE_DIR, "static")
TEMPLATES_DIR = r"D:\data-fill-system\frReport"

app.mount("/static", StaticFiles(directory=STATIC_DIR), name="static")

@app.get("/", response_class=HTMLResponse)
def index():
    html_path = os.path.join(STATIC_DIR, "index.html")
    with open(html_path, "r", encoding="utf-8") as f:
        return f.read()

@app.get("/api/preset_templates")
def list_preset_templates():
    """List available templates from frReport folder."""
    cpts = []
    for root, _, files in os.walk(TEMPLATES_DIR):
        for f in files:
            if f.lower().endswith(".cpt"):
                full_path = os.path.join(root, f)
                rel_path = os.path.relpath(full_path, TEMPLATES_DIR)
                cpts.append({"name": f, "path": full_path, "rel_path": rel_path})
    return {"total": len(cpts), "templates": cpts[:50]} # return sample for dropdown

@app.post("/api/parse_cpt")
async def parse_cpt(file: Optional[UploadFile] = File(None), template_path: Optional[str] = Form(None)):
    """Parse uploaded or preset CPT and return structural metadata."""
    if file:
        content = (await file.read()).decode("utf-8", errors="ignore")
    elif template_path and os.path.exists(template_path):
        with open(template_path, "r", encoding="utf-8", errors="ignore") as f:
            content = f.read()
    else:
        raise HTTPException(status_code=400, detail="Must provide file or template_path")

    model = CptModel(content)
    datasets = model.get_datasets()
    cells = model.get_cells()

    # Summarize cells for table view
    cell_list = list(cells.values())
    # Sort by row then col
    cell_list.sort(key=lambda x: (x["row"], x["col"]))

    return {
        "status": "success",
        "datasets": datasets,
        "total_cells": len(cell_list),
        "cells": cell_list[:120], # top rows for visualization
        "raw_xml_length": len(content)
    }

class ColumnInsertRequest(BaseModel):
    template_path: Optional[str] = None
    cpt_xml: Optional[str] = None
    insert_at_col: str # e.g. "D" or "H"
    header_title: str
    dataset_name: str
    ds_field: str
    formula: Optional[str] = ""

@app.post("/api/insert_column_and_export")
async def insert_column_and_export(req: ColumnInsertRequest):
    """
    Core Engine API:
    Inserts a new column into the CPT, shifts coordinates, rewrites formulas,
    and returns the reconstructed .cpt file!
    """
    if req.cpt_xml:
        xml_content = req.cpt_xml
    elif req.template_path and os.path.exists(req.template_path):
        with open(req.template_path, "r", encoding="utf-8", errors="ignore") as f:
            xml_content = f.read()
    else:
        raise HTTPException(status_code=400, detail="Template XML not provided")

    col_idx = col_name_to_idx(req.insert_at_col)
    model = CptModel(xml_content)
    model.insert_column(
        at_col_idx=col_idx,
        header_title=req.header_title,
        dataset_name=req.dataset_name,
        ds_field=req.ds_field,
        formula=req.formula or ""
    )

    new_xml = model.to_xml_string()
    
    filename = f"reconstructed_{req.header_title}.cpt"
    return Response(
        content=new_xml.encode('utf-8'),
        media_type="application/octet-stream",
        headers={"Content-Disposition": f"attachment; filename={filename}"}
    )

class SqlAnalyzeRequest(BaseModel):
    sql: str

@app.post("/api/analyze_sql")
def analyze_sql(req: SqlAnalyzeRequest):
    """
    Universal SQL Extractor:
    Extracts columns and parameters from user-provided SQL.
    """
    sql = req.sql.strip()
    
    # Extract params like ${param_name} or $param_name
    params = list(set(re.findall(r'\$\{?([a-zA-Z0-9_]+)\}?', sql)))
    
    # Simple regex to extract SELECT fields (from SELECT to FROM)
    fields = []
    # Match outer SELECT ... FROM
    m = re.search(r'SELECT\s+(.*?)\s+FROM\b', sql, re.IGNORECASE | re.DOTALL)
    if m:
        select_clause = m.group(1)
        # Split by comma (ignoring brackets if possible)
        raw_items = select_clause.split(",")
        for item in raw_items:
            item = item.strip()
            if not item:
                continue
            # Look for alias: AS xxx or just xxx
            alias_m = re.search(r'\bAS\s+([a-zA-Z0-9_]+)$', item, re.IGNORECASE)
            if alias_m:
                fields.append(alias_m.group(1))
            else:
                last_token = item.split()[-1]
                # clean dot
                clean_name = last_token.split(".")[-1].strip("`\"'")
                if re.match(r'^[a-zA-Z0-9_]+$', clean_name):
                    fields.append(clean_name)

    return {
        "status": "success",
        "parameters_found": params,
        "fields_found": list(dict.fromkeys(fields)) # unique preserving order
    }

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="127.0.0.1", port=8088, reload=True)
