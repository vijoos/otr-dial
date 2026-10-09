#!/usr/bin/env python3
"""Install the exact tested 2.7, seed its original storage schema, then install -r 2.8."""
import subprocess, pathlib, json, html
package='com.example.otrdial.library21preview'
def run(*args): return subprocess.run(args,check=True,text=True,capture_output=True).stdout
run('adb','install','-r','upgrade-2.7.apk')
episode=dict(id='episode:upgrade:1',source='gunsmoke',title='Upgrade preservation fixture',series='Gunsmoke',url='https://example.com/upgrade.mp3',page='https://example.com')
def seed(name, data):
    parts=['<?xml version="1.0" encoding="utf-8"?><map>']
    for k,v in data.items():
        if isinstance(v,list): parts.append('<set name="'+k+'">'+''.join('<string>'+html.escape(x)+'</string>' for x in v)+'</set>')
        elif isinstance(v,int): parts.append(f'<long name="{k}" value="{v}"/>')
        else: parts.append(f'<string name="{k}">{html.escape(v)}</string>')
    parts.append('</map>'); p=pathlib.Path('/tmp/'+name+'.xml'); p.write_text(''.join(parts))
    run('adb','push',str(p),'/data/local/tmp/'+name+'.xml')
    run('adb','shell','run-as',package,'mkdir','-p','shared_prefs')
    run('adb','shell','run-as',package,'cp','/data/local/tmp/'+name+'.xml','shared_prefs/'+name+'.xml')
seed('episode_library',dict(catalogue=json.dumps([episode]),saved=[episode['id']],follows=['gunsmoke'],queue=json.dumps([episode['id']]),last=episode['id'],**{'position_'+episode['id']:42000}))
seed('otr_dial',dict(favourites=['v13-gunsmoke-24-7']))
seed('collections24',dict(playlists=json.dumps([dict(id='upgrade-list',name='My preserved playlist',episodes=[episode['id']])]),bookmarks=json.dumps([dict(id='upgrade-bookmark',episode=episode['id'],position=42000,note='Keep this')]),sources=json.dumps([dict(id='custom-upgrade',title='My feed',kind='rss',url='https://example.com/feed',page='https://example.com',genre='Comedy')]),programmes=['Gunsmoke']))
seed('offline24',{'job:'+episode['id']:1234567})
run('adb','install','-r','app/build/outputs/apk/debug/app-debug.apk')
run('adb','install','-r','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
result=run('adb','shell','am','instrument','-w','-e','class','com.example.otrdial.Upgrade28Test',package+'.test/androidx.test.runner.AndroidJUnitRunner')
print(result)
assert 'OK (1 test)' in result, 'Upgrade preservation test failed'
