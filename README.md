# market-data

Work in progress: nothing here runs yet. The code is being built on feature branches and lands through pull requests.

market-data will be a Java (Spring Boot) service that pulls daily US equity bars from Alpaca's Market Data API into PostgreSQL, computes indicators (moving averages, volatility, ATR, 52-week range, pivot points) in SQL with window functions, and serves them through a small REST API and a chart explorer. It is meant to be the single source of market data for my other projects (a backtesting simulator and an AI trading-desk assistant) and a place to show Java and SQL work in the open.
