# Recorded pages for the macro calendar self-test

Real pages as served on 26 October 2026, gzip-compressed, used by `macro_calendar.py --self-test`
(`recorded_pages_test`): BLS release schedule (`bls_current_year`), the BLS release pages for CPI, PPI and the
employment situation, the Federal Reserve FOMC calendar and the BEA release schedule.

When a site changes its layout and the parser is adapted, refresh the files (same names, `gzip -9 -n`) and update
the expected counts and dates in `recorded_pages_test`.
