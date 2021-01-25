"""Util functions for connecting to a grpc server
"""
import socket
from socketserver import TCPServer
from subprocess import Popen

from atexit import register
from grpc import Channel, insecure_channel

from coinbase_ml.fakebase import constants as c


def create_channel(port: int) -> Channel:
    """
    create_channel creates an insecure channel to a grpc server on localhost
    """
    return insecure_channel(target=f"localhost:{port}", options=c.GRPC_CHANNEL_OPTIONS)


def get_random_free_port() -> int:
    """
    get_random_free_port returns random free port
    """
    with TCPServer(("localhost", 0), None) as s:
        return s.server_address[1]


def port_in_use(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        return s.connect_ex(("localhost", port)) == 0


def start_fakebase_server(
    port: int, test_mode: bool, terminate_automatically: bool = True
) -> Popen:
    """
    start_fakebase_server starts fakebase server on specified port in daemon process

    Will also register process with the `atexit` module so that subprocess is terminated
    when parent process exits.
    """
    process = Popen(
        [
            c.JAVA_BIN,
            f"-Xmx{c.JVM_HEAP_MEMORY}",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+UseParallelOldGC",
            "-XX:+EnableJVMCI",
            "-XX:+UseJVMCICompiler",
            "-XX:ParallelGCThreads=4",
            "-cp",
            c.FAKBASE_SERVER_JAR.as_posix(),
            c.FAKEBASE_SERVER_CLASS,
            str(port),
            str(test_mode),
        ]
    )

    if terminate_automatically:
        register(lambda process: process.terminate(), process)

    return process
