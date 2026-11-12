-- The logo fetcher got better: forget the cached "no logo" results so they are tried again.
DELETE FROM logos WHERE status = 'NONE';
