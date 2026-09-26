#!/usr/bin/env python3
"""Private local-server smoke checks. No secrets or user payloads are printed.

Production writes are confined to a unique technical account while ingress is
closed. The test removes its account/posts through the application's own API.
"""
import argparse
import base64
import hashlib
import hmac
import json
from pathlib import Path
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import uuid


def containers(project):
    ids=subprocess.check_output(['docker','ps','-q','--filter',f'label=com.docker.compose.project={project}'],text=True).split()
    return {c['Config']['Labels']['com.docker.compose.service']:c for c in json.loads(subprocess.check_output(['docker','inspect',*ids]))}


def sql(c, text):
    command=['docker','exec','-i',c['Id'],'sh','-c','exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At']
    return subprocess.check_output(command,input=text,text=True).strip()


def fingerprint(cs):
    return {table:json.loads(sql(cs[service],f"SELECT coalesce(json_object_agg(id::text,md5(row_to_json(t)::text)), '{{}}'::json) FROM (SELECT * FROM {table}) t;"))
            for table,service in [('users','postgres-user'),('posts','postgres-post')]}


def jwt(user, secret):
    key=base64.b64decode(secret)
    bits=512 if len(key)>=64 else 384 if len(key)>=48 else 256
    encode=lambda b:base64.urlsafe_b64encode(b).rstrip(b'=')
    header=encode(json.dumps({'alg':f'HS{bits}','typ':'JWT'},separators=(',',':')).encode())
    payload=encode(json.dumps({'sub':user,'iat':int(time.time()),'exp':int(time.time())+900},separators=(',',':')).encode())
    data=header+b'.'+payload
    return (data+b'.'+encode(hmac.new(key,data,getattr(hashlib,f'sha{bits}')).digest())).decode()


def graphql(base, token, query, variables=None):
    data=json.dumps({'query':query,'variables':variables or {}}).encode()
    req=urllib.request.Request(base+'/graphql',data=data,headers={'Content-Type':'application/json','Cookie':'ACCESS_TOKEN='+token,'X-Forwarded-Proto':'https'})
    with urllib.request.urlopen(req,timeout=30) as response:result=json.load(response)
    if result.get('errors'):
        # Error payloads can contain user-supplied text: print only error codes.
        raise RuntimeError('GraphQL smoke failed: '+','.join(str(e.get('extensions',{}).get('code','resolver error')) for e in result['errors']))
    return result['data']


def wait_until(check,seconds=90):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        if check():return
        time.sleep(2)
    raise RuntimeError('Timed out waiting for the test event effect')


def redis(cs,*args):
    return subprocess.check_output(['docker','exec',cs['redis']['Id'],'redis-cli','--raw',*args],text=True).strip()


def published_event(cs,service,aggregate,event_type):
    rows=sql(cs[service],f"SELECT count(*) || '|' || coalesce(bool_and(published_at IS NOT NULL),false) FROM outbox_events WHERE payload->>'aggregateId'='{aggregate}' AND payload->>'eventType'='{event_type}';")
    return rows=='1|true'


def check_old_data(cs, base, secret, before=None, minio_base='http://127.0.0.1:9000'):
    current=fingerprint(cs)
    if before:
        for table,records in before.items():
            if any(current[table].get(k)!=v for k,v in records.items()):
                raise RuntimeError('Pre-existing '+table+' changed or disappeared during deployment')
    users=list(current['users']);posts=list(current['posts'])
    if users:
        active=sql(cs['postgres-user'],"SELECT id FROM users WHERE banned_until IS NULL OR banned_until<now() ORDER BY id LIMIT 1;")
        if not active:raise RuntimeError('No active user available for the read smoke')
        token=jwt(active,secret)
        result=graphql(base,token,'query { me { id } listUniversities { id } getUnreadNotificationCount }')
        if result['me']['id']!=active:raise RuntimeError('Existing user unavailable through Gateway/gRPC')
        if posts:
            result=graphql(base,token,'query($id:ID!){getPost(id:$id){id}}',{'id':posts[0]})
            if result['getPost']['id']!=posts[0]:raise RuntimeError('Existing post unavailable')
        graphql(base,token,'query{trendingFeed(size:5){posts{id} hasMore}}')
    media=sql(cs['postgres-post'],"SELECT value FROM posts, jsonb_array_elements_text(media_urls) AS value WHERE value LIKE 'https://minio.%' LIMIT 1;")
    if not media:
        media=sql(cs['postgres-user'],"SELECT avatar_url FROM users WHERE avatar_url LIKE 'https://minio.%' LIMIT 1;")
    if media:
        path=urllib.parse.urlsplit(media).path
        with urllib.request.urlopen(minio_base+path,timeout=20) as response:
            if response.status!=200 or not response.read(1):raise RuntimeError('Existing media is unreadable')
    print(json.dumps({'existing_users':len(users),'existing_posts':len(posts),'existing_media_read':bool(media),'gateway_grpc_read':bool(users)}),flush=True)


def test_event(cs,base,secret):
    user=str(uuid.uuid4())
    username='releasecheck_'+user.replace('-','')[:12]
    sql(cs['postgres-user'],f"INSERT INTO users(id,email_google,username,name,created_at) VALUES ('{user}','{username}@example.invalid','{username}','Release verification',now());")
    token=jwt(user,secret)
    post=None
    try:
        result=graphql(base,token,'mutation($input:CreatePostInput!,$key:String!){createPost(input:$input,clientRequestId:$key){id}}',{'input':{'content':'Automatic release verification'},'key':user})
        post=result['createPost']['id']
        # Check source outbox publication and the actual read model, not just HTTP 200.
        wait_until(lambda: published_event(cs,'postgres-post',post,'POST_CREATED'))
        wait_until(lambda:bool(redis(cs,'ZSCORE','feed:author:'+user,post)))
        initial_score=float(redis(cs,'ZSCORE','feed:popular',post))
        graphql(base,token,'mutation($id:ID!){likePost(postId:$id){success}}',{'id':post})
        wait_until(lambda:graphql(base,token,'query($id:ID!){getPost(id:$id){likesCount}}',{'id':post})['getPost']['likesCount']==1)
        wait_until(lambda:float(redis(cs,'ZSCORE','feed:popular',post) or '0')>initial_score)
        graphql(base,token,'mutation($id:ID!){unlikePost(postId:$id){success}}',{'id':post})
        wait_until(lambda:published_event(cs,'postgres-post',post,'POST_UNLIKED'))
        wait_until(lambda:graphql(base,token,'query($id:ID!){getPost(id:$id){likesCount}}',{'id':post})['getPost']['likesCount']==0)
        # Reaction events carry the persisted creation timestamp; POST_CREATED
        # may instead use its envelope timestamp, a few milliseconds later.
        unliked_score=float(sql(cs['postgres-post'],f"SELECT payload->'payload'->>'createdAtMs' FROM outbox_events WHERE payload->>'aggregateId'='{post}' AND payload->>'eventType'='POST_UNLIKED';"))
        wait_until(lambda:float(redis(cs,'ZSCORE','feed:popular',post) or '0')==unliked_score)
        graphql(base,token,'mutation($id:ID!){deletePost(postId:$id){success}}',{'id':post})
        wait_until(lambda:published_event(cs,'postgres-post',post,'POST_DELETED'))
        wait_until(lambda:redis(cs,'HGET','feed:post:'+post,'deleted')=='1' and not redis(cs,'ZSCORE','feed:popular',post))
        post=None
        print('New post event reached Kafka and Feed; like/unlike/delete succeeded',flush=True)
    finally:
        original_error=sys.exc_info()[1]
        cleanup_errors=[]
        if post:
            try:
                graphql(base,token,'mutation($id:ID!){deletePost(postId:$id){success}}',{'id':post})
            except Exception as error:
                cleanup_errors.append(type(error).__name__)
        try:
            graphql(base,token,'mutation{deleteAccount{success}}')
            wait_until(lambda:sql(cs['postgres-user'],f"SELECT count(*) FROM users WHERE id='{user}';")=='0')
            wait_until(lambda:published_event(cs,'postgres-user',user,'USER_DELETED'))
            wait_until(lambda:redis(cs,'GET','feed:deleted:user:'+user)=='1')
            print('Technical account cleaned through application API',flush=True)
        except Exception as error:
            cleanup_errors.append(type(error).__name__)
        if cleanup_errors:
            print(json.dumps({'technical_account':user,'cleanup_errors':cleanup_errors}),flush=True)
            if original_error is None:
                raise RuntimeError('Technical smoke cleanup did not finish')


if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--project',required=True)
    p.add_argument('--mode',choices=['production','isolated'],default='isolated')
    p.add_argument('--base-url',default='http://127.0.0.1:8081')
    p.add_argument('--minio-base',default='http://127.0.0.1:9000')
    p.add_argument('--read-only',action='store_true')
    p.add_argument('--snapshot',type=Path)
    p.add_argument('--compare-snapshot',type=Path)
    args=p.parse_args()
    cs=containers(args.project)
    if args.snapshot:
        args.snapshot.write_text(json.dumps(fingerprint(cs)));args.snapshot.chmod(0o600)
        print('Pre-deployment record fingerprints captured')
        raise SystemExit(0)
    env=dict(x.split('=',1) for x in cs['api-gateway']['Config']['Env'] if '=' in x)
    before_path=Path('/srv/unimeow/config/data-before.json')
    if args.mode=='production' and not before_path.exists():raise RuntimeError('Production record snapshot is required')
    before=json.loads(before_path.read_text()) if args.mode=='production' else None
    if args.compare_snapshot:
        before=json.loads(args.compare_snapshot.read_text())
    check_old_data(cs,args.base_url,env['JWT_SECRET'],before,args.minio_base)
    if not args.read_only:
        if args.mode=='production' and not Path('/srv/unimeow/maintenance').is_file():
            raise RuntimeError('Production write smoke requires closed public ingress')
        test_event(cs,args.base_url,env['JWT_SECRET'])
