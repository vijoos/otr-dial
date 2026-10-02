"""Refresh the bundled browse-only catalogue from the seven curated public sources."""
import hashlib, html, json, pathlib, re, urllib.request, urllib.parse
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1] / 'app/src/main/assets/episodes'
ROOT.mkdir(parents=True, exist_ok=True)
def fetch(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'OTRDial/2.0 catalogue check'}), timeout=45).read()
def clean(value):
    return re.sub(r'\s+', ' ', re.sub('<[^>]+>', ' ', html.unescape(value or ''))).strip()
def identity(source, key):
    return 'episode:' + source + ':' + hashlib.sha256(key.encode()).hexdigest()[:24]
def duration(raw):
    try:
        result = 0
        for part in raw.split(':'): result = result * 60 + float(part)
        return int(result * 1000)
    except (TypeError, ValueError): return 0

for sid, title, feed in [('relic','The Relic Radio Show','relicradio'), ('scifi','Relic Radio Science Fiction','relicradiosciencefiction'), ('caseclosed','Case Closed','caseclosed'), ('laughs','A Legacy of Laughs','alegacyoflaughs')]:
    root = ET.fromstring(fetch('https://feeds.feedburner.com/' + feed)); episodes = []
    for item in root.findall('./channel/item')[:40]:
        enclosure = item.find('enclosure')
        if enclosure is None or not enclosure.get('type', '').startswith('audio/'): continue
        url = enclosure.get('url'); key = (item.findtext('guid') or url).strip()
        episodes.append(dict(id=identity(sid,key), source=sid, title=clean(item.findtext('title')), series=title, url=url,
            page=item.findtext('link') or 'https://www.relicradio.com/otr/', description=clean(item.findtext('description'))[:2000], date=item.findtext('pubDate') or '',
            duration=duration(item.findtext('{http://www.itunes.com/dtds/podcast-1.0.dtd}duration') or '')))
    (ROOT / (sid+'.json')).write_text(json.dumps(episodes, ensure_ascii=False, separators=(',',':')))
    print(sid, len(episodes))
for sid, title, identifier in [('gunsmoke','Gunsmoke','OTRR_Gunsmoke_Singles'), ('xminusone','X Minus One','OTRR_X_Minus_One_Singles'), ('missbrooks','Our Miss Brooks','OTRR_Our_Miss_Brooks_Singles')]:
    data = json.loads(fetch('https://archive.org/metadata/'+identifier)); assert data.get('metadata')
    assert not data['metadata'].get('access-restricted-item')
    episodes = []
    for f in sorted(data['files'], key=lambda f:f['name']):
        name = f['name']
        if not name.lower().endswith('.mp3') or f.get('private'): continue
        episodes.append(dict(id=identity(sid,name), source=sid, title=clean(f.get('title')) or name[:-4], series=title,
            url='https://archive.org/download/'+identifier+'/'+urllib.parse.quote(name,safe=''), page='https://archive.org/details/'+identifier,
            description='Old Time Radio Researchers collection on Internet Archive. File: '+name, date=f.get('album',''),duration=duration(str(f.get('length',''))),
            fallback='https://'+d['d1']+d['dir']+'/'+urllib.parse.quote(name,safe='') if (d:=data).get('d1','').endswith('.archive.org') else ''))
    (ROOT / (sid+'.json')).write_text(json.dumps(episodes, ensure_ascii=False, separators=(',',':')))
    print(sid, len(episodes))
