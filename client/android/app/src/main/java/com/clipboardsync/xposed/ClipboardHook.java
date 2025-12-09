package com.clipboardsync.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class ClipboardHook implements IXposedHookLoadPackage {
    
    private static final String TAG = "ClipboardSyncXposed";
    private static final String TARGET_PACKAGE = "com.clipboardsync";
    
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("android")) {
            return;
        }
        
        XposedBridge.log(TAG + ": Hooking system services");
        
        try {
            hookClipboardAccessAllowed(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error hooking clipboardAccessAllowed: " + t.getMessage());
        }
        
        try {
            hookShowAccessNotification(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error hooking showAccessNotification: " + t.getMessage());
        }
        
        // 防止应用被系统杀掉/冻结
        try {
            hookProcessKiller(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error hooking process killer: " + t.getMessage());
        }
        
        try {
            hookAppFreeze(lpparam);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error hooking app freeze: " + t.getMessage());
        }
    }
    
    private void hookClipboardAccessAllowed(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> clipboardServiceClass = XposedHelpers.findClassIfExists(
            "com.android.server.clipboard.ClipboardService",
            lpparam.classLoader
        );
        
        if (clipboardServiceClass == null) {
            XposedBridge.log(TAG + ": ClipboardService class not found");
            return;
        }
        
        XposedBridge.hookAllMethods(clipboardServiceClass, "clipboardAccessAllowed", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object[] args = param.args;
                String callingPackage = null;
                
                for (Object arg : args) {
                    if (arg instanceof String) {
                        callingPackage = (String) arg;
                        break;
                    }
                }
                
                if (TARGET_PACKAGE.equals(callingPackage)) {
                    XposedBridge.log(TAG + ": Allowing clipboard access for " + callingPackage);
                    param.setResult(true);
                }
            }
        });
        
        XposedBridge.log(TAG + ": Hooked clipboardAccessAllowed");
    }
    
    private void hookShowAccessNotification(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> clipboardServiceClass = XposedHelpers.findClassIfExists(
            "com.android.server.clipboard.ClipboardService",
            lpparam.classLoader
        );
        
        if (clipboardServiceClass == null) {
            return;
        }
        
        XposedBridge.hookAllMethods(clipboardServiceClass, "showAccessNotificationLocked", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object[] args = param.args;
                for (Object arg : args) {
                    if (arg instanceof String && TARGET_PACKAGE.equals(arg)) {
                        param.setResult(null);
                        return;
                    }
                }
            }
        });
    }
    
    // 防止应用被杀掉
    private void hookProcessKiller(XC_LoadPackage.LoadPackageParam lpparam) {
        // Hook ActivityManagerService 的 killProcessesBelowForeground
        Class<?> amsClass = XposedHelpers.findClassIfExists(
            "com.android.server.am.ActivityManagerService",
            lpparam.classLoader
        );
        
        if (amsClass != null) {
            // Hook forceStopPackage
            XposedBridge.hookAllMethods(amsClass, "forceStopPackage", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    for (Object arg : param.args) {
                        if (arg instanceof String && TARGET_PACKAGE.equals(arg)) {
                            XposedBridge.log(TAG + ": Preventing forceStopPackage for " + TARGET_PACKAGE);
                            param.setResult(null);
                            return;
                        }
                    }
                }
            });
            
            // Hook killBackgroundProcesses
            XposedBridge.hookAllMethods(amsClass, "killBackgroundProcesses", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    for (Object arg : param.args) {
                        if (arg instanceof String && TARGET_PACKAGE.equals(arg)) {
                            XposedBridge.log(TAG + ": Preventing killBackgroundProcesses for " + TARGET_PACKAGE);
                            param.setResult(null);
                            return;
                        }
                    }
                }
            });
            
            XposedBridge.log(TAG + ": Hooked process killer methods");
        }
        
        // Hook ProcessList 的 killPackageProcessesLocked
        Class<?> processListClass = XposedHelpers.findClassIfExists(
            "com.android.server.am.ProcessList",
            lpparam.classLoader
        );
        
        if (processListClass != null) {
            XposedBridge.hookAllMethods(processListClass, "killPackageProcessesLocked", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    for (Object arg : param.args) {
                        if (arg instanceof String && TARGET_PACKAGE.equals(arg)) {
                            XposedBridge.log(TAG + ": Preventing killPackageProcessesLocked for " + TARGET_PACKAGE);
                            param.setResult(false);
                            return;
                        }
                    }
                }
            });
        }
    }
    
    // 防止应用被冻结
    private void hookAppFreeze(XC_LoadPackage.LoadPackageParam lpparam) {
        // Hook CachedAppOptimizer (Android 11+)
        Class<?> cachedAppOptimizerClass = XposedHelpers.findClassIfExists(
            "com.android.server.am.CachedAppOptimizer",
            lpparam.classLoader
        );
        
        if (cachedAppOptimizerClass != null) {
            XposedBridge.hookAllMethods(cachedAppOptimizerClass, "freezeAppAsyncLSP", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // 检查进程名
                    for (Object arg : param.args) {
                        if (arg != null && arg.toString().contains(TARGET_PACKAGE)) {
                            XposedBridge.log(TAG + ": Preventing freeze for " + TARGET_PACKAGE);
                            param.setResult(null);
                            return;
                        }
                    }
                }
            });
            
            XposedBridge.hookAllMethods(cachedAppOptimizerClass, "freezeAppAsyncInternalLSP", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    for (Object arg : param.args) {
                        if (arg != null && arg.toString().contains(TARGET_PACKAGE)) {
                            XposedBridge.log(TAG + ": Preventing freezeInternal for " + TARGET_PACKAGE);
                            param.setResult(null);
                            return;
                        }
                    }
                }
            });
            
            XposedBridge.log(TAG + ": Hooked CachedAppOptimizer freeze methods");
        }
        
        // Hook OomAdjuster
        Class<?> oomAdjusterClass = XposedHelpers.findClassIfExists(
            "com.android.server.am.OomAdjuster",
            lpparam.classLoader
        );
        
        if (oomAdjusterClass != null) {
            XposedBridge.hookAllMethods(oomAdjusterClass, "shouldKillExcessiveProcesses", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    // 让系统认为不需要杀掉过多进程
                }
            });
        }
    }
}
