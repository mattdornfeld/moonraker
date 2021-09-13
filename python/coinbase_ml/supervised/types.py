"""Types for supervised module
"""

from typing import NewType

import pandas as pd

Features = NewType("Features", pd.DataFrame)
Prices = NewType("Prices", pd.Series)
Returns = NewType("Returns", pd.Series)
CumalativeReturns = NewType("CumalativeReturns", pd.Series)
Volatility = NewType("Volatility", pd.Series)
