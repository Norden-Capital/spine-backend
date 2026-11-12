-- Money can also be taken back out of a goal: a negative movement. A goal's saved
-- amount is the sum of its movements (never below 0, checked in the app).
ALTER TABLE wallet_goal_contributions DROP CONSTRAINT wallet_goal_contributions_amount_ck;
ALTER TABLE wallet_goal_contributions ADD CONSTRAINT wallet_goal_contributions_amount_ck CHECK (amount <> 0);
