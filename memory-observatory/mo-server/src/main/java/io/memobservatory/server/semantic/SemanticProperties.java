package io.memobservatory.server.semantic;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 语义过滤层配置（mo.semantic.*）。
 *
 * <p>默认开启，设计目标是「启动即就绪」，且<b>不依赖任何外部仓库</b>：
 * <ul>
 *   <li><b>容器</b>——laya 后端是 compose 里的独立服务（源码在项目内 {@code laya-backend/}），
 *       本层只等它就绪并自检</li>
 *   <li><b>本机</b>——若项目内的 {@code laya-backend/} 已备好 Python 环境，则由本层拉起</li>
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "mo.semantic")
public class SemanticProperties {

    /** 语义过滤总开关。 */
    private boolean enabled = true;

    /**
     * 后端不可达时，是否尝试用<b>项目自带</b>的 {@code laya-backend} 在本机拉起子进程。
     * 容器场景下后端由 compose 管理，本项不影响。
     */
    private boolean autoStartBackend = true;

    /**
     * 项目自带的 laya 后端目录（含 {@code server.py}）。留空则按
     * {@code MO_LAYA_BACKEND_DIR} → 工作目录下 {@code ../laya-backend} → {@code laya-backend} 探测。
     * <p>该目录随本仓库分发，<b>不指向任何外部仓库</b>。
     */
    private String backendDir = "";

    /** Python 解释器绝对路径。留空则按 &lt;backendDir&gt;/.venv/bin/python → python3 依次探测。 */
    private String python = "";

    /**
     * 本机形态下，若 {@code laya-backend/} 还没有 Python 环境，是否<b>在后台自动自举</b>
     * （{@code python3 -m venv .venv} + {@code pip install -r requirements.txt}）。
     *
     * <p>自举在后台线程里做，<b>不阻塞应用启动</b>；建好后再拉起后端并自检。
     * 首次约 1~3 分钟（取决于网络与是否已有 torch 缓存）。设为 false 则需手工执行
     * {@code scripts/setup-laya-backend.sh}。
     */
    private boolean autoBootstrap = true;

    /** 自举时 pip 使用的 index-url。留空用 pip 默认（官方源）。 */
    private String pipIndexUrl = "https://pypi.tuna.tsinghua.edu.cn/simple";

    /** 自举（建 venv + 装依赖）的超时上限（秒）。要覆盖 torch 的下载时间。 */
    private int bootstrapTimeoutSeconds = 1200;

    /** laya 后端地址（host 与 port 也从它解析）。容器内由 compose 注入服务名地址。 */
    private String baseUrl = "http://127.0.0.1:8770";

    /** 预加载的 checkpoint，逗号分隔。留空则后端全部懒加载（首次请求有冷启动延迟）。 */
    private String preload = "english,multilingual";

    /** 推理设备：cpu / mps / cuda。留空自动（macOS Apple Silicon 选 mps；容器内为 cpu）。 */
    private String device = "";

    /** 等待「本机拉起」的后端就绪上限（秒）。依赖已装好时远用不到这么久。 */
    private int readyTimeoutSeconds = 180;

    /**
     * 等待「外部后端」就绪的上限（秒）。走<b>后台等待，不阻塞应用启动</b>。
     * 容器首次启动要补下权重（~1.5GB）并加载两个 checkpoint，故默认给到 15 分钟。
     */
    private int externalWaitSeconds = 900;

    /**
     * 权重下载端点，传给后端子进程。官方 {@code huggingface.co} 在部分网络下不可达，
     * 默认走镜像；权重已在本地缓存时该项不生效。
     */
    private String hfEndpoint = "https://hf-mirror.com";

    /** 每次请求最多送检条数（延迟闸门；超出的候选直接保留，见 LAYA-INTEGRATION.md §4）。 */
    private int topN = 20;

    /** 单次判定 HTTP 超时。后端已 preload 时热态仅数百毫秒。 */
    private int timeoutMs = 5000;

    private double thresholdRisk = 0.95;
    private double thresholdProblem = 0.65;
    private double thresholdLoop = 0.75;
}
