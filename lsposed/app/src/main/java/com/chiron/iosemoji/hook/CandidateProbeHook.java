package com.chiron.iosemoji.hook;

import com.chiron.iosemoji.compat.Targets;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Phase 1 只读诊断：候选数据探针（H04/H05/H06）。
 *
 * <p>目的：在**不改任何行为**的前提下回答静态分析留下的问题
 * （docs/INPUT_ENGINE_MAP.md §10）：
 * <ul>
 *   <li>候选推送频率与时机；</li>
 *   <li>{@code data.b} 的实现类、候选页数、当前页偏移；</li>
 *   <li>候选条是否被清空（{@code isCandidatesListEmpty}）。</li>
 * </ul>
 *
 * <p>只在 {@code ModuleConfig.candidateProbeEnabled()} 打开时启用，日志量较大。
 */
public final class CandidateProbeHook {

    public static final String ID = "H04_candidate_probe";


    private CandidateProbeHook() {
    }

    public static void install(final ClassLoader classLoader, Targets targets) {
        final String CLS_CANDIDATE_VIEW = targets == null ? null : targets.candidateViewClass;
        final String CLS_DELEGATE = targets == null ? null : targets.candidateDelegateClass;
        if (CLS_CANDIDATE_VIEW == null) return;
        HookRegistry.install(ID, classLoader, new HookRegistry.Installer() {
            @Override
            public void install(ClassLoader cl) throws Throwable {
                // r2#Z7(data.b, int, boolean) —— 收到新候选列表的唯一入口
                XposedHelpers.findAndHookMethod(CLS_CANDIDATE_VIEW, cl, "Z7",
                        "com.sohu.inputmethod.input.api.data.b", int.class, boolean.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                describe("Z7", param.args[0], param.args[1], param.args[2]);
                            }
                        });

                // r2#N7(data.b) —— q1 的唯一赋值点
                XposedHelpers.findAndHookMethod(CLS_CANDIDATE_VIEW, cl, "N7",
                        "com.sohu.inputmethod.input.api.data.b", new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                describe("N7", param.args[0], -1, null);
                            }
                        });

                // showCandidateWordView(r2, int, boolean) —— 服务层推送时机
                XposedHelpers.findAndHookMethod(CLS_DELEGATE, cl, "showCandidateWordView",
                        CLS_CANDIDATE_VIEW, int.class, boolean.class, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                HookRegistry.log("probe showCandidateWordView page=" + param.args[1]
                                        + " anim=" + param.args[2]);
                            }
                        });
            }
        });
    }

    private static void describe(String tag, Object data, Object page, Object flag) {
        try {
            StringBuilder sb = new StringBuilder("probe ").append(tag);
            sb.append(" impl=").append(data == null ? "null" : data.getClass().getName());
            if (data != null) {
                try {
                    Object empty = XposedHelpers.callMethod(data, "isCandidatesListEmpty");
                    sb.append(" empty=").append(empty);
                    Object list = XposedHelpers.callMethod(data, "m0");
                    if (list instanceof java.util.List) {
                        java.util.List<?> l = (java.util.List<?>) list;
                        sb.append(" size=").append(l.size());
                        int n = Math.min(5, l.size());
                        sb.append(" head=[");
                        for (int i = 0; i < n; i++) {
                            if (i > 0) sb.append(',');
                            sb.append(l.get(i));
                        }
                        sb.append(']');
                    }
                    sb.append(" pages=").append(XposedHelpers.callMethod(data, "w"));
                    sb.append(" offset=").append(XposedHelpers.callMethod(data, "O"));
                } catch (Throwable ignored) {
                    // 不同版本方法可能缺失，忽略
                }
            }
            sb.append(" page=").append(page).append(" flag=").append(flag);
            HookRegistry.log(sb.toString());
        } catch (Throwable t) {
            HookRegistry.log("probe failed");
        }
    }
}
