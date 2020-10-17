"""Util functions for connecting to a grpc server
"""
from atexit import register
from socketserver import TCPServer
from subprocess import Popen

from grpc import Channel, insecure_channel

from coinbase_ml.fakebase import constants as c


def create_channel(port: int) -> Channel:
    """
    create_channel creates an insecure channel to a grpc server on localhost

    Args:
        port (int): port server is listening on

    Returns:
        Channel: insecure_channel connection to server
    """
    return insecure_channel(target=f"localhost:{port}", options=c.GRPC_CHANNEL_OPTIONS)


def get_random_free_port() -> int:
    """
    get_random_free_port returns random free port

    Returns:
        int: random free port
    """
    with TCPServer(("localhost", 0), None) as s:
        return s.server_address[1]


def start_fakebase_server(port: int, test_mode: bool) -> Popen:
    """
    start_fakebase_server starts fakebase server on specified port in daemon process

    Will also register process with the `atexit` module so that subprocess is terminated
    when parent process exits.

    Args:
        port (int): port server will listen on

    Returns:
        Popen: daemon process handle
    """
    process = Popen(
        ["java", "-jar", c.FAKBASE_SERVER_JAR.as_posix(), str(port), str(test_mode),]
    )

    register(lambda process: process.terminate(), process)

    return process
