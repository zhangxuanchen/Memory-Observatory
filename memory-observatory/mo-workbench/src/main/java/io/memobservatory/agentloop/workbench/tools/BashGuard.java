/*******************************************************************************
 * 【模块】Agent 工作台 Workbench · tools
 * 【文件】BashGuard.java（io.memobservatory.agentloop.workbench.tools）
 * 【核心功能】bash 命令黑名单按危险等级分级校验：delete 级直接拒绝，
 *            sysadmin 级需白名单环境才放开；返回 null=放行，否则拒绝原因。
 * 【核心改动】2026-08-23 迁入 mo-server 单进程。
 *******************************************************************************/
package io.memobservatory.agentloop.workbench.tools;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Bash 命令黑名单校验。按危险等级分级；放行返回 null，拒绝返回原因字符串。
 */
public final class BashGuard {

    /** 黑名单按危险等级分级：delete 级直接拒绝，sysadmin 级需白名单环境才放开。 */
    private static final List<Pattern> DENY = List.of(
            Pattern.compile("\\brm\\s+(-[a-zA-Z]*r[a-zA-Z]*f?|--recursive)"), // rm -rf / rm -r
            Pattern.compile("\\bsudo\\b"),
            Pattern.compile("\\bmkfs\\b|\\bdd\\s+if="),
            Pattern.compile("\\bshutdown\\b|\\breboot\\b|\\bpoweroff\\b"),
            Pattern.compile(">[ ]*/dev/sd"),                  // 直写磁盘设备
            Pattern.compile("\\bchmod\\s+777\\s+/"),          // 递归放开根权限
            Pattern.compile("\\b(curl|wget)[^|\\n]*\\|\\s*(ba)?sh\\b"), // 下载即执行
            Pattern.compile("\\bgit\\s+push\\s+--force\\b")   // 强推（按团队策略取舍）
    );

    private BashGuard() {
    }

    /**
     * 校验命令是否命中黑名单。
     *
     * @param command 待校验命令
     * @return null = 放行；非 null = 拒绝原因
     */
    public static String check(String command) {
        if (command == null || command.isBlank()) {
            return "命令为空";
        }
        String normalized = command.toLowerCase(Locale.ROOT);
        for (Pattern p : DENY) {
            if (p.matcher(normalized).find()) {
                return "命中黑名单规则: " + p.pattern();
            }
        }
        return null;
    }
}