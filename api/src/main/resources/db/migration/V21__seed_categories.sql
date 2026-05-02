-- System-defined categories visible to all users.
-- user_id = NULL marks these as system categories — the RLS policy
-- on categories explicitly allows NULL user_id rows through.
-- is_system = TRUE prevents users from modifying or deleting these.
INSERT INTO categories (id, user_id, name, description, is_system) VALUES
    (gen_random_uuid(), NULL, 'GROCERIES',      'Supermarkets and food shopping',           TRUE),
    (gen_random_uuid(), NULL, 'DINING',          'Restaurants, cafes and takeaway',          TRUE),
    (gen_random_uuid(), NULL, 'TRANSPORT',       'Public transport, fuel and parking',       TRUE),
    (gen_random_uuid(), NULL, 'UTILITIES',       'Electricity, gas, water and internet',     TRUE),
    (gen_random_uuid(), NULL, 'RENT',            'Rent and accommodation',                   TRUE),
    (gen_random_uuid(), NULL, 'HEALTH',          'Medical, pharmacy and fitness',            TRUE),
    (gen_random_uuid(), NULL, 'ENTERTAINMENT',   'Streaming, events and hobbies',            TRUE),
    (gen_random_uuid(), NULL, 'SHOPPING',        'Clothing, electronics and general retail', TRUE),
    (gen_random_uuid(), NULL, 'TRAVEL',          'Flights, hotels and holidays',             TRUE),
    (gen_random_uuid(), NULL, 'EDUCATION',       'Courses, books and subscriptions',         TRUE),
    (gen_random_uuid(), NULL, 'INSURANCE',       'Health, car and home insurance',           TRUE),
    (gen_random_uuid(), NULL, 'INVESTMENTS',     'Shares, ETFs and managed funds',           TRUE),
    (gen_random_uuid(), NULL, 'PERSONAL_CARE',   'Haircuts, beauty and personal products',   TRUE),
    (gen_random_uuid(), NULL, 'GIFTS',           'Gifts and donations',                      TRUE),
    (gen_random_uuid(), NULL, 'OTHER',           'Uncategorised expenses',                   TRUE);
    (gen_random_uuid(), NULL, 'UNCATEGORISED', 'Default category for unsorted expenses', TRUE),