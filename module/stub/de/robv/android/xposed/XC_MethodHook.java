package de.robv.android.xposed;

public class XC_MethodHook {
    public XC_MethodHook() {}
    public XC_MethodHook(int priority) {}

    public static class Unhook {}

    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public Object result;
        public Throwable throwable;
        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; }
        public Object getResultOrThrowable() throws Throwable { return result; }
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
}