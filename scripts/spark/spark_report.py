#!/usr/bin/env python3
# Comprehensive spark SamplerData extractor -> JSON ground truth for analysis.
import sys, struct, json

def rv(b,i):
    r=0;s=0
    while True:
        x=b[i];i+=1;r|=(x&0x7f)<<s
        if not(x&0x80):return r,i
        s+=7
def fields(b):
    i=0;n=len(b);o={}
    while i<n:
        k,i=rv(b,i);fn=k>>3;wt=k&7
        if wt==0:v,i=rv(b,i)
        elif wt==1:v=b[i:i+8];i+=8
        elif wt==2:ln,i=rv(b,i);v=b[i:i+ln];i+=ln
        elif wt==5:v=b[i:i+4];i+=4
        else:raise ValueError(wt)
        o.setdefault(fn,[]).append((wt,v))
    return o
def s(f,fn):
    for wt,v in f.get(fn,[]):
        if wt==2:return v.decode('utf-8','replace')
    return None
def i64(f,fn):
    for wt,v in f.get(fn,[]):
        if wt==0:return v
        if wt==1:return struct.unpack('<q',v)[0]
    return None
def dbl(f,fn):
    for wt,v in f.get(fn,[]):
        if wt==1:return struct.unpack('<d',v)[0]
    return None
def subs(f,fn):return [v for wt,v in f.get(fn,[]) if wt==2]
def doubles(f,fn):
    out=[]
    for wt,v in f.get(fn,[]):
        if wt==1:out.append(struct.unpack('<d',v)[0])
        elif wt==2:
            for k in range(0,(len(v)//8)*8,8):out.append(struct.unpack('<d',v[k:k+8])[0])
    return out
def varints(f,fn):
    out=[]
    for wt,v in f.get(fn,[]):
        if wt==0:out.append(v)
        elif wt==2:
            i=0
            while i<len(v):x,i=rv(v,i);out.append(x)
    return out
def mapss(f,fn):  # map<string,string>
    d={}
    for m in subs(f,fn):
        e=fields(m);d[s(e,1)]=s(e,2)
    return d

VANILLA=('net.minecraft.','com.mojang.')
JDK=('java.','javax.','jdk.','sun.','com.sun.')
LIBS=('it.unimi.dsi','io.netty','org.','gnu.trove','com.google','net.jpountz')
def is_modish(cls):
    if not cls:return False
    if cls.startswith(VANILLA) or cls.startswith(JDK) or cls.startswith(LIBS):return False
    return True
def subsystem(cls,meth):
    c=cls or ''
    if 'Unsafe' in c and meth in ('park','unpark'):return 'idle/park (main-thread waiting)'
    if c.startswith('net.minecraft.world.level.chunk') or 'PalettedContainer' in c or 'BitStorage' in c:return 'chunk block-storage'
    if 'lighting' in c or 'LightEngine' in c or 'DynamicGraphMinFixedPoint' in c:return 'lighting'
    if 'ChunkMap' in c or 'ChunkCache' in c or 'DistanceManager' in c or 'ChunkTracker' in c or 'ChunkHolder' in c or ('chunk' in c.lower() and 'world.level.chunk' not in c):return 'chunk loading/tracking'
    if 'syncher' in c or 'TrackedEntity' in c or 'ServerEntity' in c:return 'entity tracking/sync'
    if c.startswith('net.minecraft.world.entity') or 'Entity' in c and 'world.entity' in c:return 'entity tick/AI'
    if 'pathfind' in c.lower() or 'Navigation' in c or 'Goal' in c:return 'entity AI/pathfinding'
    if c.startswith('net.minecraft.network') or c.startswith('io.netty'):return 'networking'
    if c.startswith('net.minecraft.nbt') or 'serialization' in c or 'Codec' in c:return 'NBT/serialization'
    if c.startswith(JDK):return 'JDK collections/util (downstream)'
    if c.startswith('it.unimi'):return 'fastutil collections (downstream)'
    if is_modish(c):return 'MOD: '+'.'.join(c.split('.')[:3])
    return 'other vanilla'

def analyze(path):
    data=open(path,'rb').read()
    root=fields(data)
    R={'file':path}
    m=fields(subs(root,1)[0])
    R['creator']=s(fields(subs(m,1)[0]),2) if subs(m,1) else None
    R['interval_us']=i64(m,3); R['start']=i64(m,2); R['end']=i64(m,11)
    R['ticks']=i64(m,12); R['mode']={0:'EXEC',1:'ALLOC'}.get(i64(m,15)); R['engine']={0:'JAVA',1:'ASYNC'}.get(i64(m,16))
    # mods list
    mods=[]
    for me in subs(m,13):  # map<string,PluginOrModMetadata>
        e=fields(me); pm=fields(subs(e,2)[0]) if subs(e,2) else {}
        mods.append({'id':s(e,1),'name':s(pm,1),'ver':s(pm,2),'builtin':bool(i64(pm,5))})
    R['mod_count']=len(mods)
    R['mods']=sorted([x for x in mods if not x['builtin']], key=lambda z:(z['name'] or z['id'] or ''))
    # platform stats
    ps=subs(m,8)
    if ps:
        p=fields(ps[0]); R['player_count']=i64(p,7)
        tp=subs(p,4)
        if tp:t=fields(tp[0]);R['tps']=[dbl(t,1),dbl(t,2),dbl(t,3)];R['target_tps']=i64(t,4)
        mp=subs(p,5)
        if mp:
            mm=fields(mp[0]); R['mspt_ideal_max']=i64(mm,3)
            l1=subs(mm,1)
            if l1:
                rvs=fields(l1[0]); R['mspt_last1m_fields']={fn:(doubles({fn:rvs[fn]},fn) or i64({fn:rvs[fn]},fn)) for fn in rvs}
        gc={}
        for ge in subs(p,2):
            e=fields(ge); g=fields(subs(e,2)[0]) if subs(e,2) else {}
            gc[s(e,1)]={'total':i64(g,1),'avg_ms':dbl(g,2),'avg_freq_s':dbl(g,3)}
        R['gc']=gc
        mem=subs(p,1)
        if mem:
            mf=fields(mem[0]); heap=subs(mf,1)
            if heap:h=fields(heap[0]);R['heap_used']=i64(h,1);R['heap_committed']=i64(h,2);R['heap_max']=i64(h,4)
    # system stats
    ss=subs(m,9)
    if ss:
        sf=fields(ss[0]); cpu=subs(sf,1)
        if cpu:cf=fields(cpu[0]);R['cpu_threads']=i64(cf,1);R['cpu_model']=s(cf,4)
        ja=subs(sf,6)
        if ja:R['vm_args']=s(fields(ja[0]),4)
    # class -> mod source map
    class_src=mapss(root,3)
    # threads
    threads=subs(root,2)
    out_threads=[]
    for tb in threads:
        tn=fields(tb); name=s(tn,1) or '?'
        pool=subs(tn,3)
        nodes=[]
        for nb in pool:
            f=fields(nb)
            nodes.append({'cls':s(f,3) or '','m':s(f,4) or '','tot':sum(doubles(f,8)),'refs':varints(f,9)})
        agg={}; sub_agg={}; mod_agg={}; selfsum=0.0
        for nd in nodes:
            ct=sum(nodes[r]['tot'] for r in nd['refs'] if 0<=r<len(nodes))
            slf=nd['tot']-ct
            if slf<0:slf=0.0
            selfsum+=slf
            key=f"{nd['cls']}.{nd['m']}" if nd['cls'] else nd['m']
            agg[key]=agg.get(key,0.0)+slf
            sub=subsystem(nd['cls'],nd['m']); sub_agg[sub]=sub_agg.get(sub,0.0)+slf
            src=class_src.get(nd['cls'])
            if src is None and is_modish(nd['cls']): src='(modish) '+'.'.join(nd['cls'].split('.')[:3])
            if src: mod_agg[src]=mod_agg.get(src,0.0)+slf
        out_threads.append({'name':name,'nodes':len(nodes),'selfsum':selfsum,
            'top':sorted(agg.items(),key=lambda kv:-kv[1])[:120],
            'subsystems':sorted(sub_agg.items(),key=lambda kv:-kv[1]),
            'by_mod_source':sorted(mod_agg.items(),key=lambda kv:-kv[1])[:40]})
    R['threads']=out_threads
    R['class_sources_count']=len(class_src)
    return R

reports=[analyze(p) for p in sys.argv[1:]]
json.dump(reports,open('/tmp/spark_ground_truth.json','w'),indent=1)
for R in reports:
    print("="*90);print(R['file'])
    dur=(R['end']-R['start'])/1000 if R['start'] and R['end'] else None
    print(f"creator={R['creator']} mode={R['mode']} engine={R['engine']} interval={R['interval_us']}us "
          f"ticks={R['ticks']} dur={dur}s players={R.get('player_count')} tps={R.get('tps')} "
          f"mspt_ideal_max={R.get('mspt_ideal_max')}")
    print(f"cpu={R.get('cpu_threads')}c '{R.get('cpu_model')}' heap_used={R.get('heap_used')} heap_max={R.get('heap_max')} mods={R['mod_count']} class_sources={R['class_sources_count']}")
    print("GC:",R.get('gc'))
    print("vm_args:",(R.get('vm_args') or '')[:400])
    for t in R['threads']:
        print(f"\n--- thread {t['name']} nodes={t['nodes']} selfsum={t['selfsum']:.0f}ms ---")
        tot=t['selfsum'] or 1
        print("  SUBSYSTEM ROLLUP:")
        for k,v in t['subsystems'][:18]:print(f"    {100*v/tot:5.1f}% {v:9.0f}ms  {k}")
        print("  BY MOD SOURCE (spark class->mod):")
        for k,v in t['by_mod_source'][:18]:print(f"    {100*v/tot:5.1f}% {v:9.0f}ms  {k}")
print("\nWROTE /tmp/spark_ground_truth.json")
