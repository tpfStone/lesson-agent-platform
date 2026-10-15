"""Model environments must support SOCKS proxies without making external requests.

Run this with each module's Python environment after installing its dependencies.
Removing the httpx socks extra causes client construction to fail before any model call.
"""
import asyncio

try:
    import httpx
except ModuleNotFoundError:
    # Newer OpenAI SDKs use the separate httpx2 package.
    import httpx2 as httpx


def test_sync_client_can_initialize_with_socks_proxy():
    with httpx.Client(proxy="socks5://127.0.0.1:9") as client:
        assert not client.is_closed


def test_async_client_can_initialize_with_socks_proxy():
    async def check():
        async with httpx.AsyncClient(proxy="socks5://127.0.0.1:9") as client:
            assert not client.is_closed

    asyncio.run(check())
