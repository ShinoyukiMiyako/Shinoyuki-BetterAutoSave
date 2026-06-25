#!/usr/bin/env python3
# Minimal spark SamplerData (application/x-spark-sampler) protobuf analyzer.
# No external deps. Schema (spark master):
#  SamplerData{ metadata=1, threads=2(repeated ThreadNode), time_windows=6 }
#  ThreadNode{ name=1, children=3(repeated StackTraceNode POOL), times=4(double[]), children_refs=5(int32[]) }
#  StackTraceNode{ class_name=3, method_name=4, parent_line=5, line=6, method_desc=7, times=8(double[]), children_refs=9(int32[]) }
import sys, struct

def read_varint(b, i):
    r=0; s=0
    while True:
        x=b[i]; i+=1
        r |= (x & 0x7f) << s
        if not (x & 0x80): return r, i
        s += 7

def parse_fields(b):
    """Generic: returns dict field_no -> list of (wiretype, raw_value)."""
    i=0; n=len(b); out={}
    while i<n:
        key,i = read_varint(b,i)
        fn=key>>3; wt=key&7
        if wt==0:
            v,i=read_varint(b,i)
        elif wt==1:
            v=b[i:i+8]; i+=8
        elif wt==2:
            ln,i=read_varint(b,i); v=b[i:i+ln]; i+=ln
        elif wt==5:
            v=b[i:i+4]; i+=4
        else:
            raise ValueError(f"bad wiretype {wt} field {fn}")
        out.setdefault(fn,[]).append((wt,v))
    return out

def get_str(fields,fn):
    for wt,v in fields.get(fn,[]):
        if wt==2: return v.decode('utf-8','replace')
    return None

def packed_or_rep_doubles(fields,fn):
    vals=[]
    for wt,v in fields.get(fn,[]):
        if wt==1:
            vals.append(struct.unpack('<d',v)[0])
        elif wt==2:  # packed fixed64 doubles
            for k in range(0,(len(v)//8)*8,8):
                vals.append(struct.unpack('<d',v[k:k+8])[0])
    return vals

def packed_or_rep_varints(fields,fn):
    vals=[]
    for wt,v in fields.get(fn,[]):
        if wt==0:
            vals.append(v)
        elif wt==2:  # packed varints
            i=0
            while i<len(v):
                x,i=read_varint(v,i); vals.append(x)
    return vals

def submessages(fields,fn):
    return [v for wt,v in fields.get(fn,[]) if wt==2]

def analyze(path, top=25):
    data=open(path,'rb').read()
    root=parse_fields(data)
    meta_raw=submessages(root,1)
    creator=mode=engine=None; nticks=None; players=None; tps=None; mspt=None; interval=None
    if meta_raw:
        m=parse_fields(meta_raw[0])
        cr=submessages(m,1)
        if cr: creator=get_str(parse_fields(cr[0]),1)  # CommandSenderMetadata.name? field guess
        interval=None
        for wt,v in m.get(3,[]):
            if wt==0: interval=v
        for wt,v in m.get(12,[]):
            if wt==0: nticks=v
        for wt,v in m.get(15,[]):
            if wt==0: mode=v
        for wt,v in m.get(16,[]):
            if wt==0: engine=v
        ps=submessages(m,8)  # PlatformStatistics
        if ps:
            p=parse_fields(ps[0])
            for wt,v in p.get(7,[]):
                if wt==0: players=v
            tpsm=submessages(p,4)
            if tpsm:
                t=parse_fields(tpsm[0]); ds=packed_or_rep_doubles(t,1)+packed_or_rep_doubles(t,2)+packed_or_rep_doubles(t,3)
                tps=ds
            msptm=submessages(p,5)
            if msptm: mspt=parse_fields(msptm[0])

    threads=submessages(root,2)
    print(f"\n#### {path}")
    print(f"creator≈{creator} mode={'ALLOC' if mode==1 else 'EXEC'} engine={'ASYNC' if engine==1 else 'JAVA'} "
          f"interval={interval} ticks={nticks} player_count={players} tps_last(1m,5m,15m)={tps}")
    print(f"threads: {len(threads)}")

    results={}
    for tb in threads:
        tn=parse_fields(tb)
        name=get_str(tn,1) or "?"
        pool=submessages(tn,3)              # flat StackTraceNode pool
        root_refs=packed_or_rep_varints(tn,5)
        thr_times=packed_or_rep_doubles(tn,4)
        thr_total=sum(thr_times)
        # pre-parse pool nodes
        nodes=[]
        for nb in pool:
            f=parse_fields(nb)
            nodes.append({
                'cls':get_str(f,3) or '', 'm':get_str(f,4) or '',
                'tot':sum(packed_or_rep_doubles(f,8)),
                'refs':packed_or_rep_varints(f,9),
            })
        # self time = tot - sum(children tot); aggregate by class.method
        agg={}
        selfsum=0.0
        for nd in nodes:
            child_tot=sum(nodes[r]['tot'] for r in nd['refs'] if 0<=r<len(nodes))
            slf=nd['tot']-child_tot
            if slf<0: slf=0.0
            selfsum+=slf
            key=f"{nd['cls']}.{nd['m']}" if nd['cls'] else nd['m']
            agg[key]=agg.get(key,0.0)+slf
        results[name]={'total':thr_total or selfsum,'nodes':len(nodes),'agg':agg,'selfsum':selfsum}

    # print server thread (and any thread) hotspots
    order=sorted(results.items(), key=lambda kv: -kv[1]['selfsum'])
    for name,info in order[:6]:
        tot=info['selfsum'] or 1.0
        print(f"\n=== thread '{name}'  nodes={info['nodes']}  self-time-sum={info['selfsum']:.1f}ms ===")
        rows=sorted(info['agg'].items(), key=lambda kv:-kv[1])[:top]
        for k,v in rows:
            print(f"  {100*v/tot:5.1f}%  {v:10.1f}ms  {k}")

if __name__=='__main__':
    for p in sys.argv[1:]:
        analyze(p)
