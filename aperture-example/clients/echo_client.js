/**
 * EchoController 客户端 —— JavaScript(浏览器 / Node ≥ 22,零依赖,直接用全局 WebSocket)
 *
 * 端点:ws://localhost:8080/ws/echo
 * 文本帧格式:<路由键>\n<载荷 JSON>      二进制帧:<首字节 frameType><业务字节>
 *
 * 浏览器:把本文件内容粘进 DevTools Console 即可运行(自动连 ws://localhost:8080/ws/echo)。
 * Node 22+:node echo_client.js [url]
 */
(function (global) {
  'use strict';

  var DEFAULT_URL = 'ws://localhost:8080/ws/echo';
  var url = (typeof process !== 'undefined' && process.argv && process.argv[2]) || DEFAULT_URL;

  /** 建连后服务端主动下发的两条报文,不计入业务步骤 */
  var PASSIVE = ['echo.hello', 'session.ready'];

  /** 上行报文序列:① 原始载荷回显 ② DTO 载荷 ③ 二进制帧 ④ 心跳 ⑤ 校验失败 ⑥ 未注册路由 */
  var STEPS = [
    function (ws) { ws.send(frame('echo.raw', { text: 'hi' })); },
    function (ws) { ws.send(frame('echo.dto', { text: 'ab', repeat: 3 })); },
    function (ws) { ws.send(new Uint8Array([0x01, 0x7f, 0x00, 0x80])); },
    function (ws) { ws.send(frame('heartbeat.ping', {})); },
    function (ws) { ws.send(frame('echo.dto', { text: '', repeat: 3 })); },
    function (ws) { ws.send(frame('echo.not-exist', {})); }
  ];

  /** 文本帧:首行路由键,第二行载荷 JSON */
  function frame(type, payload) {
    return type + '\n' + (typeof payload === 'string' ? payload : JSON.stringify(payload));
  }

  /** 拆出首行类型(无换行时返回 null) */
  function type(raw) {
    if (typeof raw !== 'string') return null;
    var nl = raw.indexOf('\n');
    return nl < 0 ? null : raw.slice(0, nl);
  }

  /** 打印下行报文(按第一个换行拆成 类型 / 载荷) */
  function dump(dir, data) {
    if (typeof data !== 'string') {
      var bytes = new Uint8Array(data);
      var head = Array.prototype.slice.call(bytes, 0, 8)
        .map(function (b) { return ('0' + b.toString(16)).slice(-2); }).join(' ');
      console.log(dir, '<binary ' + bytes.byteLength + ' bytes> ' + head);
      return;
    }
    var nl = data.indexOf('\n');
    if (nl < 0) {
      console.log(dir, '(无分隔符)', data);
      return;
    }
    console.log(dir, data.slice(0, nl), '|', data.slice(nl + 1));
  }

  var ws = new WebSocket(url);
  ws.binaryType = 'arraybuffer';

  var next = 0;   // 下一个待发步骤
  var busy = false; // 当前步骤是否在等回执

  /** 发下一步;全部发完则等最后一条回执落地后由 onDone 关闭 */
  function pump() {
    if (busy || next >= STEPS.length) return;
    busy = true;
    console.log('--> [' + (next + 1) + '/' + STEPS.length + ']');
    STEPS[next++](ws);
  }

  ws.addEventListener('open', function () {
    console.log('已连接 ' + url);
    pump();
  });

  // 单一消费者:所有下行报文都从这里过,避免多监听器抢消息
  ws.addEventListener('message', function (ev) {
    dump('<--', ev.data);

    var t = type(ev.data);
    if (PASSIVE.indexOf(t) >= 0) return;   // 建连报文,不推进步骤

    if (!busy) return;                       // 迟到的回执,只打印
    busy = false;
    if (next >= STEPS.length) {
      ws.close(1000, 'done');
      return;
    }
    setTimeout(pump, 30);
  });

  ws.addEventListener('close', function (ev) {
    console.log('已关闭 code=' + ev.code + ' reason=' + (ev.reason || ''));
  });

  ws.addEventListener('error', function () {
    console.error('连接失败,确认服务端已启动:mvn -pl aperture-example -am spring-boot:run');
  });

  global.EchoClient = { frame: frame, url: url, socket: ws };
})(typeof globalThis !== 'undefined' ? globalThis : this);