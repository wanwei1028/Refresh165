package com.refresh165;

import java.util.*;
import java.util.regex.*;

/** Pure, fail-closed interpretation of bounded SF snapshots; no device writes. */
final class VideoEvidence {
    static final String PACKAGE = "tv.danmaku.bili";
    static final Pattern HEADER = Pattern.compile("^  Layer \\[(\\d+)\\] (.+)$");
    static final Pattern FRAME = Pattern.compile("\\bframe=(\\d+)\\b");
    static final Pattern PID = Pattern.compile("\\bownerPid=(\\d+)\\b");
    static final Pattern BOUNDS = Pattern.compile("bounds=\\{(-?\\d+),(-?\\d+),(-?\\d+),(-?\\d+)\\}");
    static final Pattern VOTE = Pattern.compile("requestedFrameRate: \\{([0-9.]+) Hz");
    static final class Sample {
        final int id, pid; final String name; final long frames, area;
        Sample(int id, int pid, String name, long frames, long area) {
            this.id=id; this.pid=pid; this.name=name; this.frames=frames; this.area=area;
        }
        boolean same(Sample s) { return s!=null && id==s.id && pid==s.pid && name.equals(s.name); }
    }
    static Sample parse(String dump, int expectedPid) {
        if (dump==null || expectedPid<=0 || dump.length()>1_500_000) return null;
        String[] lines=dump.split("\\n");
        Set<String> yuv=new HashSet<>();
        for (String line:lines) {
            if (line.contains(PACKAGE) && line.contains("requestedFrameRate:")) {
                Matcher vote=VOTE.matcher(line);
                if (vote.find()) { double fps=Double.parseDouble(vote.group(1));
                    if (fps>0 && fps<=60.5) return null; // Never overrule an explicit low-rate vote.
                }
            }
            if (!line.contains("layer:") || !line.contains("format:") || !line.contains("name:")) continue;
            String format=line.substring(line.indexOf("format:")+7).split("dataspace:",2)[0];
            if (!(format.contains("Y_CBCR") || format.contains("YCrCb") || format.contains("YCbCr")
                    || format.contains("YUV") || format.contains("NV12") || format.contains("NV21"))) continue;
            String name=line.substring(line.indexOf("name:")+5).trim();
            int end=name.indexOf(']');
            if (end>0 && name.contains("SurfaceView["+PACKAGE+"/")) yuv.add(name.substring(0,end+1));
        }
        Map<Integer,Sample> matches=new HashMap<>();
        for (int i=0;i<lines.length;i++) {
            Matcher h=HEADER.matcher(lines[i]); if (!h.matches()) continue;
            String name=h.group(2); int id=Integer.parseInt(h.group(1));
            int end=name.indexOf(']');
            if (end<0 || !name.contains("SurfaceView["+PACKAGE+"/") || !name.contains("(BLAST)#")
                    || !yuv.contains(name.substring(0,end+1))) continue;
            StringBuilder body=new StringBuilder();
            for (int j=i+1;j<lines.length && j<i+45;j++) {
                if (HEADER.matcher(lines[j]).matches()) break;
                body.append(lines[j]).append('\n');
            }
            String b=body.toString(); Matcher f=FRAME.matcher(b), p=PID.matcher(b), bounds=BOUNDS.matcher(b);
            if (!b.contains("isVisible=true") || !b.contains("isOpaque=true")
                    || !b.contains("blend=NONE (1) alpha=1.000000")
                    || !f.find() || !p.find() || !bounds.find()) continue;
            int pid=Integer.parseInt(p.group(1)); if (pid!=expectedPid) continue;
            long width=Long.parseLong(bounds.group(3))-Long.parseLong(bounds.group(1));
            long height=Long.parseLong(bounds.group(4))-Long.parseLong(bounds.group(2));
            if (width<160 || height<160 || width*height<100_000) continue;
            Sample s=new Sample(id,pid,name,Long.parseLong(f.group(1)),width*height);
            Sample old=matches.put(id,s);
            if (old!=null && (!old.same(s) || old.frames!=s.frames)) return null;
        }
        // Multiple possible video streams are ambiguous; no 'largest FPS wins'.
        return matches.size()==1 ? matches.values().iterator().next() : null;
    }
    static final class Gate {
        private Sample previous; private long at; private int highRuns,lowRuns; private boolean active;
        synchronized boolean update(Sample s,long now) {
            if (s==null) { reset(); return false; }
            long elapsed=now-at;
            if (!s.same(previous) || elapsed<300 || elapsed>2500 || s.frames<previous.frames) {
                reset(); previous=s; at=now; return false;
            }
            long delta=s.frames-previous.frames; previous=s; at=now;
            double fps=delta*1000.0/elapsed;
            if (delta==0 || fps>1000) { active=false; highRuns=lowRuns=0; return false; }
            if (fps>=85) { highRuns++; lowRuns=0; if (highRuns>=2) active=true; }
            else if (fps<=70) { highRuns=0; if (++lowRuns>=2) active=false; }
            else { highRuns=0; lowRuns=0; }
            return active;
        }
        synchronized boolean allowed(long now) { return active && now>=at && now-at<=2000; }
        synchronized void reset() { previous=null;at=0;highRuns=lowRuns=0;active=false; }
    }
}
