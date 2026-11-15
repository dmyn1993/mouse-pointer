package com.dmyn.mousepointer.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.InputEvent
import android.view.MotionEvent
import com.dmyn.mousepointer.ModuleContract
import com.dmyn.mousepointer.MousePointerConfig
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method

/**
 * Runs inside system_server (LSPosed scope: android). It hooks
 * `InputManagerService.filterInputEvent`, which the native dispatcher calls before dispatching an
 * event, and feeds touch events to [MousePointerEngine]. When the engine consumes an event the
 * Java method is skipped, so the physical event is dropped; otherwise the event is passed through
 * to the app/system unchanged.
 */
class MainHook : IXposedHookLoadPackage {

    private var configReceiver: BroadcastReceiver? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") {
            return
        }
        hookInputManager(lpparam)
    }

    private fun hookInputManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val inputManagerService = XposedHelpers.findClass(
                "com.android.server.input.InputManagerService",
                lpparam.classLoader,
            )

            val filterHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val event = param.args.getOrNull(0) as? InputEvent
                    val consumed = if (event is MotionEvent) {
                        try {
                            MousePointerEngine.handleMotionEvent(event)
                        } catch (t: Throwable) {
                            log("$TAG: handleMotionEvent failed: ${t.message}")
                            false
                        }
                    } else {
                        false
                    }
                    // true  -> skip filterInputEvent: native dispatcher passes the original event
                    // false -> physical event is dropped (engine already replayed it)
                    param.result = !consumed
                }
            }

            var hooked = false
            try {
                XposedHelpers.findAndHookMethod(
                    inputManagerService,
                    "filterInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType,
                    filterHook,
                )
                hooked = true
            } catch (_: Throwable) {
            }
            if (!hooked) {
                try {
                    XposedHelpers.findAndHookMethod(
                        inputManagerService,
                        "filterInputEvent",
                        InputEvent::class.java,
                        filterHook,
                    )
                    hooked = true
                } catch (_: Throwable) {
                }
            }
            if (!hooked) {
                for (method: Method in inputManagerService.declaredMethods) {
                    if (method.name == "filterInputEvent") {
                        try {
                            XposedBridge.hookMethod(method, filterHook)
                            hooked = true
                            break
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
            if (!hooked) {
                log("$TAG: failed to hook filterInputEvent")
            }

            enableInputFilter(inputManagerService, lpparam.classLoader)
        } catch (t: Throwable) {
            log("$TAG: hookInputManager failed: ${t.message}")
        }
    }

    /**
     * Keeps InputFilter enabled and ensures a (dummy) IInputFilter is installed so the native
     * dispatcher keeps calling filterInputEvent. Initializes [MousePointerEngine] once
     * system_server is ready and registers the config receiver.
     */
    private fun enableInputFilter(inputManagerService: Class<*>, classLoader: ClassLoader) {
        var mNativeInstance: Any? = null
        var inputManagerServiceInstance: Any? = null

        try {
            XposedHelpers.findAndHookMethod(
                inputManagerService,
                "setInputFilter",
                "android.view.IInputFilter",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args[0] == null) {
                            registerFakeInputFilter(param.thisObject, inputManagerService.classLoader!!)
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            log("$TAG: failed to hook setInputFilter: ${t.message}")
        }

        // Android 16+: com.android.server.input.NativeInputManagerService$NativeImpl
        try {
            val nativeImplClass = XposedHelpers.findClass(
                "com.android.server.input.NativeInputManagerService\$NativeImpl",
                classLoader,
            )

            XposedHelpers.findAndHookMethod(
                nativeImplClass,
                "setInputFilterEnabled",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args[0] = true
                    }
                },
            )

            XposedHelpers.findAndHookConstructor(
                inputManagerService,
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        inputManagerServiceInstance = param.thisObject
                        mNativeInstance = XposedHelpers.getObjectField(param.thisObject, "mNative")
                    }
                },
            )

            XposedHelpers.findAndHookMethod(
                inputManagerService,
                "start",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        initEngine(param.thisObject)

                        try {
                            val native = mNativeInstance
                            if (native != null) {
                                XposedHelpers.callMethod(native, "setInputFilterEnabled", true)
                            } else {
                                log("$TAG: mNative is null at start()")
                            }
                        } catch (t: Throwable) {
                            log("$TAG: failed to enable input filter: ${t.message}")
                        }

                        val ims = inputManagerServiceInstance
                        if (ims != null) {
                            val existingFilter = try {
                                XposedHelpers.getObjectField(ims, "mInputFilter")
                            } catch (_: Throwable) {
                                null
                            }
                            if (existingFilter == null) {
                                registerFakeInputFilter(ims, classLoader)
                            }
                        }
                    }
                },
            )
            return
        } catch (_: Throwable) {
        }

        // Legacy fallback: nativeSetInputFilterEnabled(long, boolean)
        try {
            val nativeMethod = inputManagerService.getDeclaredMethod(
                "nativeSetInputFilterEnabled",
                Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            )
            nativeMethod.isAccessible = true

            XposedBridge.hookMethod(nativeMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.args[param.args.size - 1] = true
                }
            })

            XposedHelpers.findAndHookMethod(
                inputManagerService,
                "start",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        initEngine(param.thisObject)
                        try {
                            val ptr = XposedHelpers.getLongField(param.thisObject, "mPtr")
                            nativeMethod.invoke(null, ptr, true)
                        } catch (t: Throwable) {
                            log("$TAG: legacy input filter enable failed: ${t.message}")
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            log("$TAG: all input filter enable approaches failed: ${t.message}")
        }
    }

    private fun initEngine(imsInstance: Any) {
        try {
            val context = XposedHelpers.getObjectField(imsInstance, "mContext") as Context
            val config = MousePointerConfig.load(context)
            MousePointerEngine.init(context, config)
            registerConfigReceiver(context, config)
        } catch (t: Throwable) {
            log("$TAG: engine init failed: ${t.message}")
        }
    }

    /**
     * The app writes nothing to Settings.Global itself; it sends the whole config as JSON in a
     * signature-permission protected broadcast, the receiver applies it and persists it.
     */
    private fun registerConfigReceiver(context: Context, initial: MousePointerConfig) {
        if (configReceiver != null) {
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent?.action != ModuleContract.ACTION_UPDATE_CONFIG) {
                    return
                }
                val raw = intent.getStringExtra(ModuleContract.EXTRA_CONFIG_JSON) ?: return
                val config = MousePointerConfig.fromJson(raw)
                MousePointerEngine.applyConfig(config)
                MousePointerConfig.save(context, config)
                log("$TAG: config updated from app")
            }
        }
        try {
            context.registerReceiver(
                receiver,
                IntentFilter(ModuleContract.ACTION_UPDATE_CONFIG),
                ModuleContract.PERMISSION_CONTROL,
                null,
                Context.RECEIVER_EXPORTED,
            )
            configReceiver = receiver
            log("$TAG: config receiver registered (enabled=${initial.enabled})")
        } catch (t: Throwable) {
            log("$TAG: register config receiver failed: ${t.message}")
        }
    }

    /**
     * Registers a dummy IInputFilter so the native dispatcher calls filterInputEvent. The Java
     * method body is never executed because the hook always decides the filter result.
     */
    private fun registerFakeInputFilter(imsInstance: Any, classLoader: ClassLoader) {
        try {
            val iInputFilterClass = XposedHelpers.findClass("android.view.IInputFilter", classLoader)

            val filterProxy = java.lang.reflect.Proxy.newProxyInstance(
                classLoader,
                arrayOf(iInputFilterClass),
                java.lang.reflect.InvocationHandler { _, method, _ ->
                    when (method.name) {
                        "install" -> log("$TAG: input filter installed")
                        "asBinder" -> android.os.Binder()
                        else -> null
                    }
                    null
                },
            )

            XposedHelpers.callMethod(imsInstance, "setInputFilter", filterProxy)
        } catch (e: Exception) {
            log("$TAG: registerFakeInputFilter failed: ${e.message}")
        }
    }

    private fun log(message: String) {
        XposedBridge.log(message)
        Log.i(TAG, message.removePrefix("$TAG: "))
    }

    private companion object {
        const val TAG = "MousePointer"
    }
}
