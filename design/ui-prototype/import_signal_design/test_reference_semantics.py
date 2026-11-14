from reference_semantics import *
from datetime import datetime,timedelta,timezone
checks=0

def check(value):
 global checks
 assert value
 checks+=1

def rejects(fn):
 try:fn()
 except ValueError:return True
 return False

check(classify_import('a','a','b')=='NEW')
check(classify_import('a','b','c')=='CONFLICT')
check(classify_import('a','b','b')=='UNCHANGED')
check(classify_import('a','b','a')=='UNCHANGED')
check(classify_import(None,None,'b')=='NEW')
check(classify_import(None,'a','b')=='CONFLICT')
check(classify_import('a','a','b',scope_complete=False,explicit_withdrawal=True)=='INVALID')
check(classify_import('a','a','b',explicit_withdrawal=True)=='WITHDRAWN')
check(classify_import('a','a','b',valid=False)=='INVALID')
check(normalize_target(720,'seconds','minutes','stable-30m','stable-30m')==12)
check(normalize_target(12,'minutes','seconds','stable-30m','stable-30m')==720)
check(normalize_target('.98','fraction','percent','100-tasks','100-tasks',allow_fraction=True)==98)
check(rejects(lambda:normalize_target('.98','fraction','percent','100-tasks','100-tasks')))
check(rejects(lambda:normalize_target(61,'seconds','minutes','x','x')))
check(rejects(lambda:normalize_target(10,'minutes','minutes','30-days','calendar-month')))
check(rejects(lambda:normalize_target(-1,'minutes','minutes','x','x')))
check(rejects(lambda:normalize_target('NaN','minutes','minutes','x','x')))
check(rejects(lambda:normalize_target(101,'percent','percent','x','x')))
check(duplicate_disposition(None,'a')=='NEW')
check(duplicate_disposition('a','a')=='REPLAY')
check(duplicate_disposition('a','b')=='CONFLICT')
now=datetime(2026,10,31,18,0,tzinfo=timezone.utc)
check(freshness(now-timedelta(seconds=121),now,None,now,120)=='UNKNOWN')
check(freshness(now-timedelta(seconds=120),now,None,now,120)=='WITHIN_AGE_LIMIT_NOT_QUALIFIED')
check(freshness(now-timedelta(days=365),now,now,now,3600,basis='SOURCE_CHECKED_AT')=='WITHIN_AGE_LIMIT_NOT_QUALIFIED')
check(freshness(now,now,None,now,3600,basis='SOURCE_CHECKED_AT')=='UNKNOWN')
check(freshness(now+timedelta(seconds=1),now,None,now,120)=='UNKNOWN')
print(f'{checks} finite import/freshness reference checks passed; no live integration or acceptance established.')
