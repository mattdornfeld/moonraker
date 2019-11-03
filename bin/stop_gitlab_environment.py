#!python3
"""Contacts Gitlab API to stop environment
"""
import logging
import os
from typing import Dict

import requests
from requests.models import Response

logging.basicConfig(
    level=logging.INFO,
    format="[%(asctime)s] {%(pathname)s:%(lineno)d} %(levelname)s - %(message)s",
)

LOGGER = logging.getLogger(__name__)


def get(url: str, headers: Dict[str, str]) -> Response:
    """
    get [summary]

    Args:
        url (str): [description]
        headers (Dict[str, str]): [description]

    Returns:
        Response: [description]
    """
    response = requests.get(url, headers=headers)
    raise_for_status(response)

    return response


def post(url: str, headers: Dict[str, str]) -> Response:
    """
    post [summary]

    Args:
        url (str): [description]
        headers (Dict[str, str]): [description]

    Returns:
        Response: [description]
    """
    response = requests.post(url, headers=headers)
    raise_for_status(response)

    return response


def raise_for_status(response: Response) -> None:
    """
    raise_for_status [summary]

    Args:
        response (Response): [description]

    Returns:
        None: [description]
    """
    response.raise_for_status()
    LOGGER.info(response.json())


if __name__ == "__main__":
    CI_TOKEN = os.environ["CI_TOKEN"]
    CI_PROJECT_NAMESPACE = "moonraker"
    CI_PROJECT_NAME = "coinbase_train"
    ENVIRONMENT = os.environ["ENVIRONMENT"]

    LOGGER.info("Getting info about environment %s", ENVIRONMENT)

    HEADERS = {"PRIVATE-TOKEN": CI_TOKEN}

    GITLAB_API_URL = "https://gitlab.com/api/v4/projects/"

    GET_ENVIRONMENT_URL = (
        GITLAB_API_URL + f"{CI_PROJECT_NAMESPACE}%2F{CI_PROJECT_NAME}/"
        f"environments?name={ENVIRONMENT}"
    )

    RESPONSE = get(GET_ENVIRONMENT_URL, HEADERS)

    ENVIRONMENT_ID = RESPONSE.json()[0]["id"]

    LOGGER.info("Stopping environment %s", ENVIRONMENT_ID)

    STOP_ENVIRONMENT_URL = (
        GITLAB_API_URL + f"{CI_PROJECT_NAMESPACE}%2F{CI_PROJECT_NAME}/"
        f"environments/{ENVIRONMENT_ID}/stop"
    )

    RESPONSE = post(STOP_ENVIRONMENT_URL, HEADERS)
