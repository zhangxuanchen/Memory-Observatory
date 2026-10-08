package io.memobservatory.exoskeleton.model;

/**
 * 决策卡片模板库（设计文档 §4.10.2）。
 *
 * 固定六个，由人维护，不随数据自动增减；卡的文案与证据由系统生成。
 * 选项一律三档、位置固定、默认项是「不动」，逃逸口不占选项位。
 */
public enum CardTemplate {
    T1("规则审批"),
    T2("档位调整"),
    T3("硬化处置"),
    T4("校准抽检"),
    T5("样本不足"),
    T6("矛盾裁决");

    private final String label;

    CardTemplate(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
