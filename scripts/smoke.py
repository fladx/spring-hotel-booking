#!/usr/bin/env python3
"""Smoke test against already running local Gateway; creates only demo data."""
import json, os, uuid
from datetime import date, timedelta
from urllib import request, error
BASE = os.environ.get('GATEWAY_URL', 'http://localhost:8080')
def call(method, path, data=None, token=None, expected=200):
    headers={'Content-Type':'application/json','X-Request-Id':'smoke-'+uuid.uuid4().hex}
    if token: headers['Authorization']='Bearer '+token
    r=request.Request(BASE+path, data=None if data is None else json.dumps(data).encode(), headers=headers, method=method)
    try:
        with request.urlopen(r, timeout=10) as result: status, body=result.status,result.read()
    except error.HTTPError as e: status,body=e.code,e.read()
    assert status==expected, (method,path,status,body.decode())
    return json.loads(body) if body else None
username='smoke'+uuid.uuid4().hex
user=call('POST','/api/auth/register',{'username':username,'password':'smoke-password-123'}, expected=201)['accessToken']
assert call('GET','/api/hotels',token=user)['totalElements'] >= 2
key=uuid.uuid4().hex
start = date.today() + timedelta(days=30)
payload={'requestId':key,'autoSelect':True,'hotelId':1,'startDate':str(start),'endDate':str(start+timedelta(days=4))}
booking=call('POST','/api/bookings',payload,user,201)
assert booking['status']=='CONFIRMED',booking
repeat=call('POST','/api/bookings',payload,user,201)
assert repeat['id']==booking['id'],(booking,repeat)
other=call('POST','/api/auth/register',{'username':'other'+uuid.uuid4().hex,'password':'smoke-password-123'},expected=201)['accessToken']
call('GET','/api/bookings/'+str(booking['id']),token=other,expected=404)
call('GET','/api/users',token=user,expected=403)
call('GET','/api/bookings',expected=401)
call('POST','/internal/rooms/release',{'requestId':key},user,403)
cancelled=call('DELETE','/api/bookings/'+str(booking['id']),token=user)
assert cancelled['status']=='CANCELLED' and not cancelled['compensationPending'],cancelled
print('PASS: Gateway discovery routes, JWT, hotel search, booking, idempotency, ownership, roles, internal denial, compensation')
