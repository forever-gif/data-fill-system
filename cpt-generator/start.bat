@echo off
chcp 65001 >nul
cd /d "D:\data-fill-system\cpt-generator"
echo Starting CPT Intelligent Workbench on http://127.0.0.1:8088 ...
python -m uvicorn main:app --host 127.0.0.1 --port 8088 --reload
pause
