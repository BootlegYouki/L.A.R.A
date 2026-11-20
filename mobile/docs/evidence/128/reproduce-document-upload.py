"""Reproduce mock submission kinds. Run only against a disposable Hub on 127.0.0.1:38080.

The image and PDF are minimal synthetic byte sequences to test type handling, not rendering.
The script creates synthetic assignments and submissions in the test Hub.
"""
import io, json, urllib.request, uuid, zipfile
base='http://127.0.0.1:38080'
def request(path, body=None, headers=None, method=None):
    req=urllib.request.Request(base+path,body,headers or {},method=method)
    with urllib.request.urlopen(req,timeout=10) as r: return r.status,r.read()
def get_file(path, token):
    req=urllib.request.Request(base+path, headers={'Authorization':'Bearer '+token})
    with urllib.request.urlopen(req,timeout=10) as r: return r.status,r.read(),r.headers.get('Content-Type')
def json_call(path, value, token=None):
    h={'Content-Type':'application/json'}
    if token: h['Authorization']='Bearer '+token
    return json.loads(request(path,json.dumps(value).encode(),h)[1])
teacher=json_call('/api/auth/login',{'lrn_or_id':'T-0001','pin':'123456'})['token']
learner=json_call('/api/auth/login',{'lrn_or_id':'123456789012','pin':'123456'})['token']
classes=json.loads(request('/api/classrooms',headers={'Authorization':'Bearer '+teacher})[1]); classroom=classes[0]['id']
word=io.BytesIO()
with zipfile.ZipFile(word,'w',zipfile.ZIP_DEFLATED) as z:
    z.writestr('[Content_Types].xml', 'synthetic types')
    z.writestr('word/document.xml','<w:document><w:p><w:r><w:t>Synthetic work</w:t></w:r></w:p></w:document>')
fixtures=[('image.jpg','image/jpeg',b'\xff\xd8\xff\xe0synthetic','IMAGE'),('work.pdf','application/pdf',b'%PDF-1.4\n%%EOF\n','DOCUMENT'),('work.docx','application/vnd.openxmlformats-officedocument.wordprocessingml.document',word.getvalue(),'DOCUMENT')]
for name,mime,data,expected in fixtures:
    assignment=json_call('/api/assignments',{'classroom_id':classroom,'title':'Synthetic upload','instructions':'Synthetic','max_points':10,'due_date':4102444800000,'allow_late':True},teacher)['id']
    sid=str(uuid.uuid4()); boundary='lara'+uuid.uuid4().hex
    parts=[('--'+boundary+'\r\nContent-Disposition: form-data; name="submission_id"\r\n\r\n'+sid+'\r\n').encode(),('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="'+name+'"\r\nContent-Type: '+mime+'\r\n\r\n').encode(),data,('\r\n--'+boundary+'--\r\n').encode()]
    status,raw=request('/api/assignments/'+assignment+'/submit',b''.join(parts),{'Authorization':'Bearer '+learner,'Content-Type':'multipart/form-data; boundary='+boundary})
    response=json.loads(raw); kind=response['file_type']
    listed=json.loads(request('/api/assignments/'+assignment+'/submissions',headers={'Authorization':'Bearer '+teacher})[1])[0]['file_type']
    status2,content,content_type=get_file('/api/submissions/'+sid+'/file',teacher)
    print(f'{name}: request MIME={mime}; upload HTTP={status}; returned={kind}; expected={expected}; list={listed}; file HTTP={status2}; response MIME={content_type}; bytes_match={content==data}')
