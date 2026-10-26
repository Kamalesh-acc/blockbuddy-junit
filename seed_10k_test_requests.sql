-- BlockBuddy: 10,000 TEST requests for JMeter.
-- Run ONLY in the TEST Supabase project (SQL Editor).
--
-- One-time, in the TEST project only:   create table bb_test_marker (x int);
-- The guard below stops this script on any database without that table (the live one).
--
-- Change VIEWER_HANDLE to the handle of the JMeter test account. That account will see
-- these requests in its feed.

do $$
begin
  if to_regclass('public.bb_test_marker') is null then
    raise exception 'STOP: this is not the test database (no bb_test_marker table).';
  end if;
end $$;

-- Seed user who "posts" the requests (seed users may have no auth_id).
insert into users (name, handle, verified_vit)
values ('JMeter Seed', 'jmeter_seed', true)
on conflict (handle) do nothing;

with seed as (select id from users where handle = 'jmeter_seed'),
     viewer as (select id from users where handle = 'VIEWER_HANDLE'),
     ins as (
       insert into requests (requester_id, kind, what, place, status, created_at)
       select seed.id, 'item', 'TEST load request #' || g, 'TEST place', 'open',
              now() - make_interval(secs => g)
       from seed, generate_series(1, 10000) as g
       returning id
     )
insert into request_audience_friends (request_id, user_id)
select ins.id, viewer.id from ins, viewer;

-- Check:
select count(*) from requests r join users u on u.id = r.requester_id where u.handle = 'jmeter_seed';

-- CLEAN UP after the load test (cascades to the requests and audience rows):
-- delete from users where handle = 'jmeter_seed';
