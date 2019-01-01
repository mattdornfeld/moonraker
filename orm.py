from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy import (create_engine, Column, Integer, BigInteger, String, 
	DateTime, Float)
from gdax_train.constants import *

Base = declarative_base()

class GDAXTrade(Base):

	__tablename__ = 'gdax_trades'

	row_id = Column( BigInteger, primary_key = True, autoincrement = True )
	order_id = Column( String )
	price = Column( Float )
	funds = Column( Float )
	maker_order_id = Column( String )
	taker_order_id = Column( String )
	trade_id = Column( String )
	product_id = Column( String )
	client_oid = Column( String )
	reason = Column( String )
	remaining_size = Column( Float )
	size = Column( Float )
	sequence = Column( BigInteger )
	side = Column( String )
	time = Column( DateTime )
	order_type = Column( String )
	event_type = Column( String )

if __name__ == '__main__':
	engine = create_engine('postgresql://{}:{}@{}/{}'.format(
		POSTGRES_USERNAME, POSTGRES_PASSWORD, DB_HOST, DB_NAME))

	Base.metadata.create_all(engine)