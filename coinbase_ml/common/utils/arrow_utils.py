"""Utils for working with Apache Arrow
"""
from pathlib import Path
from typing import List

from pandas import DataFrame
from pyarrow import ipc


def read_from_arrow_socket(socket_file_path: Path) -> List[DataFrame]:
    """Read a stream of messages from an Arrow socket and convert them
    to a list of Pandas DataFrames.
    """
    with open(socket_file_path, "rb") as reader_f:
        reader = ipc.open_stream(reader_f)
        dfs: List[DataFrame] = [m.to_pandas() for m in reader]

    return dfs
