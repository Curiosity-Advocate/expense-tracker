-- System categories visible to all users.
-- user_id = NULL marks these as system categories — the RLS policy on categories
-- explicitly allows NULL user_id rows through to every authenticated user.
-- System categories are immutable (enforced by the trigger in V15).
INSERT INTO categories (id, user_id, name, description) VALUES
    (gen_random_uuid(), NULL, 'UNCATEGORISED',  'Default for unsorted expenses'),
    (gen_random_uuid(), NULL, 'GROCERIES',      'Supermarkets and food shopping'),
    (gen_random_uuid(), NULL, 'DINING',         'Restaurants, cafes and takeaway'),
    (gen_random_uuid(), NULL, 'TRANSPORT',      'Public transport, fuel and parking'),
    (gen_random_uuid(), NULL, 'UTILITIES',      'Electricity, gas, water and internet'),
    (gen_random_uuid(), NULL, 'RENT',           'Rent and accommodation'),
    (gen_random_uuid(), NULL, 'HEALTH',         'Medical, pharmacy and fitness'),
    (gen_random_uuid(), NULL, 'ENTERTAINMENT',  'Streaming, events and hobbies'),
    (gen_random_uuid(), NULL, 'SHOPPING',       'Clothing, electronics and general retail'),
    (gen_random_uuid(), NULL, 'TRAVEL',         'Flights, hotels and holidays'),
    (gen_random_uuid(), NULL, 'EDUCATION',      'Courses, books and subscriptions'),
    (gen_random_uuid(), NULL, 'INSURANCE',      'Health, car and home insurance'),
    (gen_random_uuid(), NULL, 'INVESTMENTS',    'Shares, ETFs and managed funds'),
    (gen_random_uuid(), NULL, 'PERSONAL_CARE',  'Haircuts, beauty and personal products'),
    (gen_random_uuid(), NULL, 'GIFTS',          'Gifts and donations'),
    (gen_random_uuid(), NULL, 'OTHER',          'Everything else');
