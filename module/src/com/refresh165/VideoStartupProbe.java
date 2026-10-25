package com.refresh165;

/** Finite startup window, never a steady-playback polling loop. */
final class VideoStartupProbe {
    static final int MAX_ATTEMPTS=6;
    static final long MAX_WINDOW_MS=6000;
    enum Result { WAIT, HIGH, NATIVE, EXHAUSTED }
    private final VideoEvidence.Gate gate=new VideoEvidence.Gate();
    private final long start;
    private VideoEvidence.Sample previous;
    private int attempts,valid;
    private boolean finished;
    VideoStartupProbe(long start){this.start=start;}
    boolean canRead(long now){return !finished && attempts<MAX_ATTEMPTS && now>=start && now-start<MAX_WINDOW_MS;}
    Result observe(VideoEvidence.Sample sample,long now) {
        if(!canRead(now)){finished=true;return Result.EXHAUSTED;}
        attempts++;
        if(sample==null){valid=0;previous=null;gate.reset();}
        else {
            valid=sample.same(previous)?valid+1:1;previous=sample;
            if(gate.update(sample,now)){finished=true;return Result.HIGH;}
            if(valid>=3){finished=true;return Result.NATIVE;}
        }
        if(attempts>=MAX_ATTEMPTS){finished=true;return Result.EXHAUSTED;}
        return Result.WAIT;
    }
    int attempts(){return attempts;}
}
