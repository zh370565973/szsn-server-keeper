<template>
  <div class="page guide">
    <a-card size="small" title="这个系统做什么">
      <p>
        本系统跑在这台服务器上，用来查看 CPU、内存、磁盘、网络和 JVM，并在你明确打开控制开关之后，按设定目标占用<strong>本进程自己的</strong> CPU 或内存。
      </p>
      <p>
        它不能把其他程序已经占用的资源压下去。页面上的目标是尽量靠近，不是操作系统配额。请以「实际使用率」「出力」和「受限原因」为准，不要只看你填的数字。
      </p>
    </a-card>

    <a-card size="small" title="页面怎么找">
      <p>顶部是主菜单，左侧是当前主菜单下的子菜单。点顶部某一项，会进入该项的第一个子页面。</p>
      <ul>
        <li><strong>运行监控</strong>：总览、历史监控。先看机器现在和过去怎么样。</li>
        <li><strong>负载控制</strong>：资源控制。当场填写参数并启动、暂停、停止这一次负载。</li>
        <li><strong>任务调度</strong>：任务模板、定时计划、执行记录。把参数存下来，按时间重复跑，并回看每次结果。</li>
        <li><strong>系统</strong>：系统设置、使用说明。开关、急停锁存和本页说明。</li>
      </ul>
      <p>
        右上角显示负载引擎是否接入、控制开关和急停状态。<strong>紧急停止</strong>会立刻停掉负载，关闭控制开关并锁存。<strong>退出</strong>结束本次登录。
      </p>
    </a-card>

    <a-card size="small" class="span-2" title="建议按这个顺序用">
      <ol class="two-col steps">
        <li>打开<strong>总览</strong>，确认采样正常。数据过期或不可用时不要启动负载。</li>
        <li>到<strong>系统设置</strong>打开控制开关。打开开关本身不会开始加压，也不会补跑错过的计划。</li>
        <li>到<strong>资源控制</strong>填写目标。不确定能不能开时，先点「预检」，预检不会真的加压。</li>
        <li>需要以后重复使用时，把同一组参数存成<strong>任务模板</strong>再运行。</li>
        <li>需要到点自动跑时，用有限时长的模板做<strong>定时计划</strong>。</li>
        <li>跑完后到<strong>执行记录</strong>看这一次的结果，到<strong>历史监控</strong>看一段时间内的曲线。</li>
      </ol>
    </a-card>

    <a-card size="small" class="span-2" title="各页面怎么用">
      <div class="section-grid">
        <section>
          <h3>总览</h3>
          <p>看当前 CPU、内存、网络、磁盘、JVM 和最近一段时间的曲线。采样不正常时，先处理采集问题，再考虑加压。</p>
        </section>
        <section>
          <h3>历史监控</h3>
          <p>可按 1 小时、24 小时、7 天查看采样。监控曲线保留 7 天，过期后这里不再画出那段曲线。执行摘要仍留在执行记录里。</p>
        </section>
        <section class="span-2">
          <h3>资源控制</h3>
          <p>同一时间只能有一次负载。可选只压 CPU、只占内存，或两边一起。两边一起时，有一边没起来，两边一起停。</p>
          <ul class="two-col bullets">
            <li>CPU 目标是整台机器的使用率。别的程序已经更高时，本系统压不下去。</li>
            <li>内存可以「多占固定大小」或「按系统占用百分比」。固定大小必须是 4 MiB 的整数倍。超过当前预算会被拒绝，你填的数不会被悄悄改小。</li>
            <li>有限时长最短 10 秒、最长 7 天，到点自己停。中间暂停的时间也算在里面。</li>
            <li>持续运行不会自己停，必须自己点「停止」，或用右上角「紧急停止」。</li>
            <li>暂停会放开这次抓着的内存；恢复前会再检查能不能开。</li>
            <li>停止后「负载持有」回到 0。操作系统看到的进程驻留内存往往还要过一会儿才下降，这不表示没有停掉。</li>
          </ul>
        </section>
        <section>
          <h3>任务模板</h3>
          <p>把一组参数存下来再运行。修改模板不会改变已经启动的实例，那一次仍按启动时的快照跑。被定时计划引用的模板不能删除。持续任务在启动前会再确认一次。</p>
        </section>
        <section>
          <h3>定时计划</h3>
          <p>只能选择有限时长的模板。支持一次、每天、每周和固定间隔，不提供 Cron。时间按本机时区换算成服务器上的绝对时间。关闭或删除计划不会停掉已经启动的实例，那一次会继续，直到自己结束或被停止。</p>
        </section>
        <section>
          <h3>执行记录</h3>
          <p>按状态、模板编号和时间查看每次执行，点进去可看详情。运行中修改目标不会写入这条记录的目标变更。</p>
        </section>
        <section>
          <h3>系统设置</h3>
          <p>
            在这里打开或关闭控制开关，以及解除急停锁存。阈值、监听地址和部署位置是只读的，网页不能改口令，也不能再加用户。
            解除锁存需要关键指标连续健康、并且没有正在跑的实例；解开之后开关仍是关闭的，要再打开才能启动。
          </p>
        </section>
      </div>
    </a-card>

    <a-card size="small" class="span-2" title="使用注意事项">
      <ul class="two-col bullets">
        <li>默认只在本机打开。不要把控制页暴露到公网；改成远程访问必须同时配好证书和允许的网段，否则进程不会启动。</li>
        <li>进程每次重启后，控制开关都是关的，需要重新打开。重启不会自动把上次的负载再跑起来。</li>
        <li>非 Linux，或机器上还有额外的 cgroup、cpuset 限制时，不能启动负载。页面会说明当前环境不可用。</li>
        <li>出现异常时优先点右上角「紧急停止」。急停会锁存，解开之前不能再启动新的负载。</li>
        <li>内存只占用本进程的 Java 堆，按块慢慢增加，并且受系统预留、堆预留和本次任务上限约束。给不出那么多时会拒绝，而不是硬挤。</li>
        <li>同一时间只有一个执行实例。有实例在跑时，资源控制和任务模板都不能再开一个。</li>
        <li>持续任务不会到点停止。业务高峰时不要随便开持续运行。</li>
        <li>登录连续失败多次后会冷却一段时间，请稍后再试，不要反复提交。</li>
        <li>只有一个管理员。用户名和口令写在服务器配置里，不在这个页面上修改。</li>
      </ul>
    </a-card>
  </div>
</template>

<style scoped>
.page.guide {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 8px;
  align-items: stretch;
  max-width: none;
}
.guide :deep(.ant-card) {
  height: 100%;
}
.guide :deep(.ant-card-body) {
  color: #1f1f1f;
  line-height: 1.7;
}
.guide p,
.guide ul,
.guide ol {
  margin: 0 0 8px;
}
.guide p:last-child,
.guide ul:last-child,
.guide ol:last-child {
  margin-bottom: 0;
}
.guide h3 {
  margin: 0 0 6px;
  font-size: 15px;
  font-weight: 700;
}
.guide ul,
.guide ol {
  padding-left: 22px;
}
.section-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 14px 28px;
  align-items: start;
}
.two-col {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  column-gap: 28px;
  row-gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}
.two-col li {
  position: relative;
  min-width: 0;
  padding-left: 1.35em;
}
.steps {
  counter-reset: step;
}
.steps li {
  counter-increment: step;
}
.steps li::before {
  content: counter(step) ".";
  position: absolute;
  left: 0;
  font-weight: 700;
}
.bullets li::before {
  content: "";
  position: absolute;
  left: 0.35em;
  top: 0.65em;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
}
@media (max-width: 1100px) {
  .page.guide,
  .section-grid,
  .two-col {
    grid-template-columns: 1fr;
  }
}
</style>
