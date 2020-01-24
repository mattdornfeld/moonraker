"""
 [summary]
"""
import os

from fakebase_tests.conftest import create_database_in_container, wait_for_database

import pytest


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
    container = create_database_in_container(
        f"{os.getcwd()}/coinbase_ml_tests/data/moonraker.sql"
    )
    wait_for_database()
    yield
    container.stop()
