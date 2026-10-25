package com.aiagent.knowledge.infrastructure.splitter;

import dev.langchain4j.model.openai.OpenAiTokenizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归用例：切分链路曾经依赖 langchain4j 的 DocumentBySentenceSplitter，
 * 该类绑定 opennlp-tools 1.9.x 的 sentDetect(String)，与 pom 锁定的 2.x 二进制不兼容，
 * 运行期抛 NoSuchMethodError 导致文档入库全部失败。
 */
class TextSplitterTest {

    private final TextSplitter textSplitter = new TextSplitter(new OpenAiTokenizer());

    @Test
    void shouldSplitMultiParagraphChineseTextWithoutOpenNlpSentenceDetector() {
        String text = """
                退款流程说明。用户提交退款申请后，客服会在一个工作日内审核。
                审核通过后原路退回，到账时间以支付渠道为准。若超时未到账请联系客服。

                售后规则说明。商品签收后七天内支持无理由退货。
                定制类商品不支持退货，质量问题除外。退货运费由责任方承担。

                发票开具说明。订单完成后可在个人中心申请电子发票。
                发票金额以实付金额为准，不支持修改抬头以外的信息。
                """;

        var segments = textSplitter.split(text, 100, 20);

        assertThat(segments).isNotEmpty();
        assertThat(segments).allSatisfy(segment -> assertThat(segment.text()).isNotBlank());
        assertThat(segments).anySatisfy(segment -> assertThat(segment.text()).contains("退款流程说明"));
        assertThat(segments).anySatisfy(segment -> assertThat(segment.text()).contains("发票开具说明"));
    }

    @Test
    void shouldReturnSingleSegmentForShortText() {
        var segments = textSplitter.split("退款时效为三个工作日。", 100, 20);

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).text()).isEqualTo("退款时效为三个工作日。");
    }
}
