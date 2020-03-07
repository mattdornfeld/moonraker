"""
 [summary]
"""
import os
import platform
from shutil import copyfile
from time import sleep

import pytest
import docker
from docker.client import DockerClient
from docker.models.containers import Container
from sqlalchemy import create_engine  # pylint: disable=C0411
from sqlalchemy.exc import OperationalError  # pylint: disable=C0411

from coinbase_ml.fakebase import constants as c
from . import constants as tc


def _wait_for_running(container: Container) -> None:
    while container.attrs["State"]["Running"] is not True:
        sleep(1.0)
        container.reload()


def _wait_for_healthy(container: Container) -> None:
    while container.attrs["State"]["Health"]["Status"] != "healthy":
        sleep(1.0)
        container.reload()


def _start_postgres_container(client: DockerClient) -> Container:
    try:
        container = client.containers.run(
            detach=True,
            environment=dict(
                POSTGRES_DB=c.DB_NAME,
                POSTGRES_PASSWORD=c.POSTGRES_PASSWORD,
                POSTGRES_USERNAME=c.POSTGRES_USERNAME,
            ),
            healthcheck=dict(
                test=["CMD-SHELL", "pg_isready -U postgres"],
                interval=int(3e9),  # 3 seconds
                timeout=int(3e9),
                retries=10,
            ),
            image=tc.POSTGRES_IMAGE_NAME,
            name=tc.POSTGRES_CONTAINER_NAME,
            ports={f"{c.DB_PORT}/tcp": None},
            volumes=[
                f"{tc.TMP_SQL_DIR}:/docker-entrypoint-initdb.d",
                "/tmp/fakebase/postgres:/var/lib/postgresql/data",
            ],
        )
    except docker.errors.APIError:
        container = client.containers.list(
            filters=dict(name=tc.POSTGRES_CONTAINER_NAME, status="exited")
        )[0]

        container.start()

    return container


def create_database_in_container(test_data_src: str) -> Container:
    """
    create_database_in_container [summary]

    Args:
        test_data_src (str): [description]

    Returns:
        Container: [description]
    """
    os.makedirs(tc.TMP_SQL_DIR, exist_ok=True)
    copyfile(src=test_data_src, dst=f"{tc.TMP_SQL_DIR}/moonraker.sql")

    client = docker.from_env()

    containers = client.containers.list(filters=dict(name=tc.POSTGRES_CONTAINER_NAME))

    if not containers:
        container = _start_postgres_container(client)
    else:
        container = containers[0]

    _wait_for_running(container)
    _wait_for_healthy(container)

    if platform.system() == "Linux":
        db_host = container.attrs["NetworkSettings"]["Networks"]["bridge"]["IPAddress"]
        db_port = c.DB_PORT
    else:
        db_host = "localhost"
        db_port = container.attrs["NetworkSettings"]["Ports"][f"{c.DB_PORT}/tcp"][0][
            "HostPort"
        ]

    c.ENGINE = create_engine(
        f"postgresql://{c.POSTGRES_USERNAME}:{c.POSTGRES_PASSWORD}@"
        f"{db_host}:{db_port}/{c.DB_NAME}",
        pool_size=c.SQLALCHEMY_POOL_SIZE,
    )

    return container


@pytest.fixture(  # type: ignore
    params=[pytest.param("integration", marks=pytest.mark.integration_tests)],
    scope="session",
)
def create_database_stop_when_finished() -> None:
    """
    create_database [summary]

    Returns:
        None: [description]
    """
    container = create_database_in_container(tc.TEST_SQL_SRC_DIR)
    wait_for_database()
    yield
    container.stop()


def wait_for_database() -> None:
    """
    wait_for_database [summary]
    """
    while True:
        try:
            c.ENGINE.connect().execute("select * from coinbase_cancellations")
            return
        except OperationalError:
            print("Database is not ready.")
            sleep(5)
