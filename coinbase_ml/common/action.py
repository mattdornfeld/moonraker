from datetime import datetime


class ActionBase:
    def cancel_expired_orders(self, current_dt: datetime) -> None:
        pass

    def execute(self) -> None:
        pass
