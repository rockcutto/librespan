package eu.siacs.conversations.services;

/** Explicit Apply handler. Reading/rendering the page does not use this command boundary. */
public final class PushSettingsUiCommands {
    public interface Operations {
        PushSettingsConfiguration.Snapshot read();
        boolean save(String gateway,String vapid,String mode,String provider);
        boolean connect(String gateway,String vapid,String mode,String provider);
    }
    private boolean running;
    private long lastRetry = Long.MIN_VALUE;
    public enum RetryResult { REQUESTED, IGNORED, FAILED }
    /** UI click guard only. Does not implement subscription retry scheduling. */
    public RetryResult retry(Runnable operation, long elapsedRealtime) {
        if(running || (lastRetry != Long.MIN_VALUE && elapsedRealtime-lastRetry < 1500L))
            return RetryResult.IGNORED;
        running=true;lastRetry=elapsedRealtime;
        try {operation.run();return RetryResult.REQUESTED;}
        catch(RuntimeException failure){return RetryResult.FAILED;}
        finally{running=false;}
    }

    public boolean apply(Operations operations,String gateway,String vapid,String mode,String provider) {
        if(running || !PushSettingsConfiguration.valid(gateway,vapid)) return false;
        running=true;
        try {
            PushSettingsConfiguration.Snapshot current=operations.read();
            if(current.cancelling)return false;
            if(current.enabled&&current.matches(gateway,vapid,mode,provider))return true;
            if(!current.enabled&&!operations.save(gateway,vapid,mode,provider))return false;
            return operations.connect(gateway,vapid,mode,provider);
        } finally {running=false;}
    }
}
