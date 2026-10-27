package cn.huimeng.service.impl;

import java.util.ArrayDeque;
import org.springframework.stereotype.Component;

/**
 * 单卡本地执行器的串行闸门。
 *
 * <p>ComfyUI 只有一张 12GB 显卡，多个任务同时提交会让模型反复换入换出，单张图片从几十秒涨到十几分钟。
 * 平台对外仍让每个请求各自保持连接并持续收到流式心跳（前端一直转圈），但真正提交给执行器的任务 严格按到达顺序一次一个。
 *
 * <p>用显式票号而不是 {@link java.util.concurrent.Semaphore}：公平信号量在排队期间无法检查用户 是否取消，也无法在不破坏公平性的前提下每秒给客户端写心跳。
 */
@Component
public class MediaSerialGate {
  private final Object lock = new Object();
  private final ArrayDeque<Long> waiters = new ArrayDeque<>();
  private long nextTicket = 1;
  private boolean busy;

  /** 取号进入队列；取得执行权后必须调用 {@link #release()}，取消/超时则调用 {@link #abandon(long)}。 */
  public long enter() {
    synchronized (lock) {
      long ticket = nextTicket++;
      waiters.addLast(ticket);
      lock.notifyAll();
      return ticket;
    }
  }

  /** 只有队首且执行器空闲时才能取得执行权；返回 false 表示继续排队。 */
  public boolean tryTurn(long ticket) {
    synchronized (lock) {
      Long head = waiters.peekFirst();
      if (busy || head == null || head.longValue() != ticket) return false;
      waiters.removeFirst();
      busy = true;
      return true;
    }
  }

  /** 排队期间取消或超时：把自己从队列摘除，不能影响后面的人。 */
  public void abandon(long ticket) {
    synchronized (lock) {
      waiters.remove(ticket);
      lock.notifyAll();
    }
  }

  /** 执行结束（成功、失败、超时、中断）都必须释放，否则后续任务永久排队。 */
  public void release() {
    synchronized (lock) {
      busy = false;
      lock.notifyAll();
    }
  }

  /** 当前仍在排队（不含正在执行）的任务数，用于日志与验收。 */
  public int waiting() {
    synchronized (lock) {
      return waiters.size();
    }
  }
}
