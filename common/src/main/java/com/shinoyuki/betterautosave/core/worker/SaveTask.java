package com.shinoyuki.betterautosave.core.worker;

public interface SaveTask {

    String taskName();

    void execute() throws Exception;

    void onUnhandledError(Throwable cause);
}
