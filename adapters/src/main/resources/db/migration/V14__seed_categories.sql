-- System categories visible to all users.
-- user_id = NULL marks these as system categories — the RLS policy on categories
-- explicitly allows NULL user_id rows through to every authenticated user.
-- System categories are immutable (enforced by the trigger in V15).
INSERT INTO categories (id, user_id, name, description) VALUES
    (gen_random_uuid(), NULL, 'Uncategorised',  'Default for unsorted expenses'),
    (gen_random_uuid(), NULL, 'Groceries',      'Supermarkets and food shopping'),
    (gen_random_uuid(), NULL, 'Dining',         'Restaurants, cafes and takeaway'),
    (gen_random_uuid(), NULL, 'Transport',      'Public transport, fuel and parking'),
    (gen_random_uuid(), NULL, 'Utilities',      'Electricity, gas, water and internet'),
    (gen_random_uuid(), NULL, 'Rent',           'Rent and accommodation'),
    (gen_random_uuid(), NULL, 'Health',         'Medical, pharmacy and fitness'),
    (gen_random_uuid(), NULL, 'Entertainment',  'Streaming, events and hobbies'),
    (gen_random_uuid(), NULL, 'Shopping',       'Clothing, electronics and general retail'),
    (gen_random_uuid(), NULL, 'Travel',         'Flights, hotels and holidays'),
    (gen_random_uuid(), NULL, 'Education',      'Courses, books and subscriptions'),
    (gen_random_uuid(), NULL, 'Insurance',      'Health, car and home insurance'),
    (gen_random_uuid(), NULL, 'Investments',    'Shares, ETFs and managed funds'),
    (gen_random_uuid(), NULL, 'Personal Care',  'Haircuts, beauty and personal products'),
    (gen_random_uuid(), NULL, 'Gifts',          'Gifts and donations'),
    (gen_random_uuid(), NULL, 'Other',          'Everything else');
