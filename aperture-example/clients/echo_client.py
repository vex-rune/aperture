"""EchoController 客户端 —— Python(asyncio,依赖 websockets)

pip install websockets
python echo_client.py [ws://localhost:8080/ws/echo]

文本帧格式:<路由键>\\n<载荷 JSON>      二进制帧:<首字节 frameType><业务字节>
"""

import asyncio
import json
import sys

import websockets

DEFAULT_URL = "ws://localhost:8080/ws/echo"


def frame(msg_type: str, payload: dict | str = "") -> str:
    """文本帧:首行路由键,第二行载荷 JSON"""
    body = payload if isinstance(payload, str) else json.dumps(payload, separators=(",", ":"))
    return f"{msg_type}\n{body}"


def dump(direction: str, raw) -> None:
    """打印下行报文(按第一个换行拆成 类型 / 载荷)"""
    if isinstance(raw, str):
        text = raw
    else:
        print(f"{direction} <binary {len(raw)} bytes> {raw[:8].hex()}")
        return
    type_, _, body = text.partition("\n")
    print(f"{direction} {type_} | {body}")


async def main(url: str) -> None:
    async with websockets.connect(url) as ws:
        print(f"已连接 {url}")

        async def recv_one() -> None:
            dump("<--", await ws.recv())

        # 建连后服务端主动下发 echo.hello + session.ready
        await recv_one()
        await recv_one()

        # ① 原始载荷回显:received 里是载荷 JSON 原文
        await ws.send(frame("echo.raw", {"text": "hi"}))
        await recv_one()

        # ② DTO 载荷:text 按 repeat 重复,下行带 sessionId
        await ws.send(frame("echo.dto", {"text": "ab", "repeat": 3}))
        await recv_one()

        # ③ 二进制帧:首字节 0x01 作 frameType,其余字节原样透传
        await ws.send(bytes([0x01, 0x7F, 0x00, 0x80]))
        await recv_one()

        # ④ 客户端心跳(服务端开启 client-heartbeat 时才有回执)
        await ws.send(frame("heartbeat.ping", {}))
        await recv_one()

        # ⑤ 校验失败:text 为空触发 @NotBlank,回 error{bad_request}
        await ws.send(frame("echo.dto", {"text": "", "repeat": 3}))
        await recv_one()

        # ⑥ 未注册路由:回 error{not_found}
        await ws.send(frame("echo.not-exist", {}))
        await recv_one()

        await ws.close()
        print("已关闭")


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1] if len(sys.argv) > 1 else DEFAULT_URL))