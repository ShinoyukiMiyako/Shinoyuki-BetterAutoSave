#!/usr/bin/env python3
# Spark SamplerData call-tree extractor: inclusive hot paths on the Server thread,
# plus targeted inclusive% for save/autosave/chunkmap frames. Reuses field nums from README.
import sys, struct

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

data=open(sys.argv[1],'rb').read()
root=fields(data)
threads=subs(root,2)
TARGET=sys.argv[2] if len(sys.argv)>2 else 'Server thread'

for tb in threads:
    tn=fields(tb); name=s(tn,1) or '?'
    if TARGET.lower() not in name.lower():continue
    pool=subs(tn,3)
    nodes=[]
    for nb in pool:
        f=fields(nb)
        nodes.append({'cls':s(f,3) or '','m':s(f,4) or '','line':None,
                      'tot':sum(doubles(f,8)),'refs':varints(f,9)})
    roots=varints(tn,5)
    total=sum(nodes[r]['tot'] for r in roots if 0<=r<len(nodes))
    print(f"THREAD: {name}  nodes={len(nodes)} roots={len(roots)} inclusive_total={total:.0f}ms")
    def label(i):
        nd=nodes[i]; c=nd['cls'];
        cc=c.split('.')[-1] if c else ''
        return (cc+'.'+nd['m']) if cc else nd['m']

    # 1) Greedy heaviest path from the top root(s)
    rs=sorted(roots,key=lambda r:-nodes[r]['tot'])
    print("\n== HEAVIEST CALL PATHS (greedy follow biggest child) ==")
    for r in rs[:3]:
        print(f"\nroot {label(r)}  incl={nodes[r]['tot']:.0f}ms ({100*nodes[r]['tot']/total:.1f}%)")
        cur=r; depth=0; seen=set()
        while True:
            nd=nodes[cur]
            ct=sum(nodes[c]['tot'] for c in nd['refs'] if 0<=c<len(nodes))
            slf=nd['tot']-ct
            child=[c for c in nd['refs'] if 0<=c<len(nodes)]
            child.sort(key=lambda c:-nodes[c]['tot'])
            big=child[0] if child else None
            print(f"  {'  '*depth}{label(cur)}  incl={nd['tot']:.0f}ms self={slf:.0f}ms"
                  + (f"  -> {len(child)} children" if child else "  [leaf]"))
            if big is None or depth>=24 or big in seen: break
            seen.add(big); cur=big; depth+=1

    # 2) Targeted inclusive aggregation for save/chunk frames
    KEYS=['saveallchunks','saveeverything','m_8807_','autosave','chunkmap','save(',
          '.save','tickchunks','m_140258_','processunloads','m_201698_','scheduleunload',
          'serializer','palettedcontainer','betterautosave','flush']
    agg={}
    for i,nd in enumerate(nodes):
        key=(nd['cls']+'.'+nd['m']).lower()
        for kk in KEYS:
            if kk in key:
                agg.setdefault(kk,[0.0,0]); agg[kk][0]+=nd['tot']; agg[kk][1]+=1
    print("\n== INCLUSIVE TIME of save/chunk-related frames (sum of node inclusive, may double-count nested) ==")
    for kk,(t,c) in sorted(agg.items(),key=lambda kv:-kv[1][0]):
        print(f"  {kk:22s} {t:9.0f}ms  {100*t/total:5.1f}%  nodes={c}")

    # 3) Find the single biggest-inclusive frame matching saveAllChunks / autosave entry and dump its top children
    def find_first(substrs):
        for i,nd in enumerate(nodes):
            key=(nd['cls']+'.'+nd['m']).lower()
            if any(x in key for x in substrs): return i
        return None
    for tag,subset in [('saveAllChunks-ish',['saveallchunks','m_129880_','m_129881_']),
                       ('autosave/tickChunks',['tickchunks','m_184098_','autosave']),
                       ('ChunkMap.save',['m_140258_']),
                       ('processUnloads',['m_140280_','processunloads'])]:
        i=find_first(subset)
        if i is not None:
            nd=nodes[i]
            ch=sorted([c for c in nd['refs'] if 0<=c<len(nodes)],key=lambda c:-nodes[c]['tot'])
            print(f"\n== frame [{tag}] {label(i)} incl={nd['tot']:.0f}ms ({100*nd['tot']/total:.1f}%) top children:")
            for c in ch[:10]:
                cnd=nodes[c]
                print(f"    {label(c)}  incl={cnd['tot']:.0f}ms ({100*cnd['tot']/total:.1f}%)")
