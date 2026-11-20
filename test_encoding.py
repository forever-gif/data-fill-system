import os
p = 'backend/src/main/java/com/example/datafill/service/feishu/approval/FeishuApprovalAutomationService.java'
c = open(p, 'rb').read()
# detect utf-16 le
if c.startswith(b'\xff\xfe'):
    text = c.decode('utf-16le')
    open(p, 'w', encoding='utf-8').write(text)
    print('Converted from utf-16le to utf-8 successfully')
else:
    print('Already', len(c))
