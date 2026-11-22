#!/usr/bin/env python3
from __future__ import annotations
from pathlib import Path
from urllib.parse import quote, urljoin, urlparse
from urllib.request import Request, build_opener, HTTPCookieProcessor
from http.cookiejar import CookieJar
import json, re, ssl, sys

ROOT=Path(sys.argv[1]); OUT=Path(sys.argv[2])
BUNDLED=ROOT/'ci/fixtures/bundled'
UA='NoodleMobile-DeepPlaybackProbe/0.10'
opener=build_opener(HTTPCookieProcessor(CookieJar()))

def fetch(url):
    try:
        req=Request(url,headers={'User-Agent':UA,'Accept':'text/html,application/xhtml+xml,*/*;q=0.4','Accept-Language':'ru,en;q=0.7'})
        with opener.open(req,timeout=20) as r:
            raw=r.read(2*1024*1024)
            return {'ok':True,'status':getattr(r,'status',200),'final_url':r.geturl(),'text':raw.decode('utf-8','replace')}
    except Exception as e:
        return {'ok':False,'status':getattr(e,'code',None),'final_url':getattr(e,'url',url),'error':f'{type(e).__name__}: {e}','text':''}

def host(u,base=''):
    try:return (urlparse(urljoin(base,u.replace('\\/','/'))).hostname or '').lower()
    except:return ''

def anchors(text):
    out=[]
    for m in re.finditer(r'<a\b[^>]*\bhref\s*=\s*(["\'])(.*?)\1[^>]*>',text,re.I|re.S): out.append(m.group(2))
    return out

def choose_watch(text,base,engine):
    for raw in anchors(text):
        u=urljoin(base,raw); p=urlparse(u).path.lower()
        if any(x in p for x in ('/feed','/trending','/categories','/tags','/search/')): continue
        if engine=='kvs_view' and re.match(r'^/(?:view/\d+|video/[^/]+|videos/[^/]+)/?$',p): return u
        if engine=='kvs_videos' and re.match(r'^/(?:video|videos)/[^/]+/?$',p): return u
        if engine=='noodle_watch_playlist' and '/watch/' in p: return u
        if engine=='novideo_embed' and '/novideo/' in p: return u
    return ''

def extract_hosts(text,base):
    iframe=[]
    for m in re.finditer(r'<iframe\b[^>]*\bsrc\s*=\s*(["\'])(.*?)\1',text,re.I|re.S):
        u=urljoin(base,m.group(2)); h=host(u)
        if h and u not in iframe: iframe.append(u)
    media=set()
    for m in re.finditer(r'https?:\\?/\\?/[^"\'<>\\\s]+?(?:\.m3u8|\.mpd|\.mp4)(?:\?[^"\'<>\\\s]*)?',text,re.I):
        h=host(m.group(0),base)
        if h: media.add(h)
    return iframe,sorted(media)

def search_urls(a):
    b=a['base_url'].rstrip('/'); q=quote('секс',safe=''); e=a.get('engine','')
    if e=='noodle_watch_playlist': return [f'{b}/video/{q}',f'{b}/search/?q={q}']
    if e=='novideo_embed': return [f'{b}/search/?q={q}',f'{b}/{q}/']
    return [f'{b}/search/?q={q}',f'{b}/search/{q}/']

report={'schema':1,'purpose':'playback host compatibility; no titles or page bodies retained','sources':[]}
for p in sorted(BUNDLED.glob('*/adapter.json')):
    a=json.loads(p.read_text(encoding='utf-8-sig')); row={'id':a['id'],'engine':a.get('engine'),'allowed_hosts':a.get('permissions',{}).get('allowed_hosts',[])}
    selected=None; watch=''
    for su in search_urls(a):
        r=fetch(su)
        if not r['ok']: continue
        w=choose_watch(r['text'],r['final_url'],a.get('engine',''))
        if w: selected=r; watch=w; break
    row['search_ok']=bool(selected); row['search_final_host']=host(selected['final_url']) if selected else ''
    row['watch_kind']='/' + (urlparse(watch).path.strip('/').split('/',1)[0] if watch else '') + '/'
    if watch:
        wr=fetch(watch); row['watch_status']=wr.get('status'); row['watch_final_host']=host(wr.get('final_url',''))
        if wr.get('ok'):
            iframes,media=extract_hosts(wr['text'],wr['final_url']); row['watch_iframe_hosts']=sorted({host(u) for u in iframes if host(u)}); row['watch_media_hosts']=media
            if iframes:
                ir=fetch(iframes[0]); row['iframe_status']=ir.get('status'); row['iframe_final_host']=host(ir.get('final_url',''))
                if ir.get('ok'):
                    nested,nmedia=extract_hosts(ir['text'],ir['final_url']); row['iframe_nested_hosts']=sorted({host(u) for u in nested if host(u)}); row['iframe_media_hosts']=nmedia
    report['sources'].append(row)
OUT.parent.mkdir(parents=True,exist_ok=True); OUT.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8'); print(json.dumps(report,ensure_ascii=False,indent=2))
