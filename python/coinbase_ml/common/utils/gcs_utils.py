"""
 [summary]
"""
import logging
from pathlib import Path
from typing import Optional, BinaryIO, IO

from google.cloud import storage
from google.oauth2.service_account import Credentials
from sacred.run import Run

from coinbase_ml.common import constants as c

LOGGER = logging.getLogger(__name__)


def download_file_from_gcs(
    bucket_name: str,
    credentials_path: Path,
    gcp_project_name: str,
    key: str,
    file: Optional[IO[bytes]] = None,
    filename: Optional[Path] = None,
) -> None:
    """
    download_file_from_gcs [summary]

    Args:
        bucket_name (str): [description]
        credentials_path (Path): [description]
        gcp_project_name (str): [description]
        key (str): [description]
        file (Optional[IO[bytes]], optional): [description]. Defaults to None.
        filename (Optional[Path], optional): [description]. Defaults to None.
    """
    credentials = Credentials.from_service_account_file(credentials_path)
    client = storage.Client(project=gcp_project_name, credentials=credentials)
    bucket = client.bucket(bucket_name)
    blob = bucket.get_blob(key)
    if file:
        blob.download_to_file(file)
        file.seek(0)
    elif filename:
        blob.download_to_filename(filename)
    else:
        raise ValueError("Must specify file or filename.")


def get_gcs_base_path(_run: Run) -> str:
    """
    get_gcs_base_path [summary]

    Args:
        _run (Run): [description]

    Returns:
        str: [description]
    """
    ex_name = _run.experiment_info["name"]
    ex_id = _run._id  # pylint: disable=W0212
    environment = c.ENVIRONMENT.replace("/", "-")
    return f"{environment}/{ex_name}-{ex_id}"


def upload_file_to_gcs(
    bucket_name: str,
    credentials_path: Path,
    gcp_project_name: str,
    key: str,
    file: Optional[BinaryIO] = None,
    filename: Optional[Path] = None,
) -> None:
    """
    upload_file_to_gcs [summary]

    Args:
        bucket_name (str): [description]
        credentials_path (Path): [description]
        gcp_project_name (str): [description]
        key (str): [description]
        file (Optional[BinaryIO], optional): [description]. Defaults to None.
        filename (Optional[Path], optional): [description]. Defaults to None.

    Returns:
        None: [description]
    """
    credentials = Credentials.from_service_account_file(credentials_path)
    client = storage.Client(project=gcp_project_name, credentials=credentials)
    bucket = client.bucket(bucket_name)
    LOGGER.info("uploading %s to %s...", key, bucket_name)
    blob = bucket.blob(key)

    if file:
        blob.upload_from_file(file)
    elif filename:
        blob.upload_from_filename(filename.as_posix())
    else:
        ValueError("specify exactly one of 'file' or 'file_name'")
