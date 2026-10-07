"""Import the user's researched workbook without treating web pages as audio URLs.

Usage: python3 scripts/import-directory.py path/to/OTR_Source_Directory.xlsx
Requires openpyxl. Existing station IDs are retained for favourites and history.
"""
import json
import sys
from pathlib import Path
import openpyxl

root = Path(__file__).resolve().parents[1]
book = openpyxl.load_workbook(sys.argv[1], data_only=True)
entries = []
radio_map = {
    9:'pump-1940-home',10:'pump-american-gold',11:'pump-britcom1',12:'pump-britcom2',13:'pump-britcom3',14:'pump-britcom4',15:'pump-crime',16:'pump-strange',17:'pump-venture',18:'pump-britannia',
    19:'rokit-1940s',20:'rokit-adventure',21:'rokit-american-classics',22:'rokit-american-comedy',23:'rokit-britcom1',24:'rokit-britcom2',25:'rokit-comedy-gold',26:'rokit-crime-extra',27:'rokit-crime-suspense',28:'rokit-drama',29:'rokit-mystery',30:'rokit-nostalgia',31:'rokit-old-time-gold',32:'rokit-saturn-x',33:'rokit-scifi',
    35:'v13-relic-radio-on-the-air',36:'yusa-red',37:'yusa-blue',38:'conyers-main',39:'conyers-holiday',40:'catalogue-antioch',41:'v13-crimetime-old-time-radio',42:'catalogue-amazing-tales',43:'v13-american-legend-old-time-radio',45:'v13-wrcw-gunsmoke',46:'v13-wrcw-old-time-westerns',47:'v13-theater-channel',50:'v13-crime-detectives',51:'v13-gunsmoke-24-7',53:'v13-wild-west-tales',54:'v13-suspense-24-7',55:'v13-crime-fighter-detectives',56:'v13-1640-am-america-comedy',57:'v13-mystery-and-suspense-radio-network'
}
feed_map = {2:'https://feeds.feedburner.com/horror',3:'https://feeds.feedburner.com/relicradiostrangetales',6:'https://feeds.feedburner.com/relicradiothrillers',8:'https://feeds.feedburner.com/orsonwelles',10:'https://feed.podbean.com/choiceclassicradio/feed.xml'}
for sheet, category in [('Web Radio (24-7)','radio'),('Podcasts','podcast'),('YouTube','youtube'),('Archives','archive'),('Apps & Community','other')]:
    for r in list(book[sheet].values)[1:]:
        num = r[0]
        if category in ('radio','podcast'):
            title,page,genre,notes,warning = r[1],r[3],r[4],r[9 if category=='radio' else 8],r[10 if category=='radio' else 9]
        elif category=='youtube': title,page,genre,notes,warning = r[1],r[2],r[3],r[6],r[7]
        elif category=='archive': title,page,genre,notes,warning = r[1],r[2],r[3],r[8],r[9]
        else: title,page,genre,notes,warning = r[1],r[3],r[4],r[7],''
        d = dict(id=f'{category}-{num:02}',title=title,page=page,genre=genre,category=category,notes=notes or '',warning=warning or '',inventoryDate='2026-10-06')
        if category=='radio' and num in radio_map: d['stationId']=radio_map[num]
        if category=='podcast':
            if num in {1: 'relic',4:'scifi',5:'caseclosed',7:'laughs'}: d['sourceId']={1: 'relic',4:'scifi',5:'caseclosed',7:'laughs'}[num]
            elif num in feed_map: d['feed']=feed_map[num]
        if category=='archive' and num>=11:
            if num==11:d['sourceId']='gunsmoke'
            elif num==15:d['warning']='Archive metadata returned no individual playable audio files on 7 October 2026. Open the collection website.'
            else:d['archive']=page.rsplit('/',1)[1]
        if category=='youtube' and num==5:
            d['page']='https://www.youtube.com/@oldtimeretroradio'
            d['warning']='Channel link published on the project website; availability controlled by YouTube.'
        entries.append(d)
assert len(entries)==100
entries.append(dict(id='archive-otrcat',title='OTRCAT — Free daily selections',category='archive',page='https://www.otrcat.com/',genre='Drama',notes='Today, Yesterday and 2 Days Ago. Opens the original free daily download section.',warning='Website access; automatic daily import is not included.'))
stations=json.loads((root/'app/src/main/assets/stations.json').read_text())
assert all(not d.get('stationId') or d['stationId'] in {s['id'] for s in stations} for d in entries)
(root/'app/src/main/assets/source-directory.json').write_text(json.dumps(entries,indent=2,ensure_ascii=False)+'\n')
print(f'{len(entries)} directory entries, {len(radio_map)} matched existing stations, {len(feed_map)} new RSS imports, 6 new Archive imports.')
