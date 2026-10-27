package cn.huimeng;

import static org.assertj.core.api.Assertions.*;

import cn.huimeng.service.impl.MediaSerialGate;
import org.junit.jupiter.api.Test;

/** 单卡闸门的顺序与取消语义；不涉及数据库或执行器。 */
class MediaSerialGateTests {

  @Test
  void onlyTheHeadOfTheQueueCanTakeTheExecutor() {
    var gate = new MediaSerialGate();
    long first = gate.enter();
    long second = gate.enter();

    assertThat(gate.tryTurn(second)).isFalse();
    assertThat(gate.tryTurn(first)).isTrue();
    assertThat(gate.tryTurn(second)).isFalse();

    gate.release();
    assertThat(gate.tryTurn(second)).isTrue();
    gate.release();
    assertThat(gate.waiting()).isZero();
  }

  @Test
  void abandoningAQueuedTicketLetsTheNextOneThrough() {
    var gate = new MediaSerialGate();
    long first = gate.enter();
    long cancelled = gate.enter();
    long third = gate.enter();
    assertThat(gate.tryTurn(first)).isTrue();

    gate.abandon(cancelled);
    gate.release();

    assertThat(gate.tryTurn(third)).isTrue();
    gate.release();
  }

  @Test
  void waitingCountExcludesTheRunningTask() {
    var gate = new MediaSerialGate();
    long first = gate.enter();
    gate.enter();
    gate.enter();
    assertThat(gate.waiting()).isEqualTo(3);

    assertThat(gate.tryTurn(first)).isTrue();
    assertThat(gate.waiting()).isEqualTo(2);

    gate.release();
    assertThat(gate.waiting()).isEqualTo(2);
  }
}
