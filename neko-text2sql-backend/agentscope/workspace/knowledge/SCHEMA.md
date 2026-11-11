# Demo Database Schema

Sample e-commerce schema used to ground Text2SqlAgent. Replace with your real schema.

## customers

| column       | type         | notes             |
|--------------|--------------|-------------------|
| id           | BIGINT PK    | surrogate key     |
| name         | VARCHAR(100) | customer name     |
| email        | VARCHAR(255) | unique            |
| city         | VARCHAR(100) | billing city      |
| created_at   | DATETIME     | registration time |

## products

| column       | type          | notes             |
|--------------|---------------|-------------------|
| id           | BIGINT PK     | surrogate key     |
| name         | VARCHAR(150)  | product name      |
| category     | VARCHAR(100)  | product category  |
| price        | DECIMAL(10,2) | unit price        |

## orders

| column       | type          | notes                          |
|--------------|---------------|--------------------------------|
| id           | BIGINT PK     | surrogate key                  |
| customer_id  | BIGINT FK     | -> customers.id                |
| status       | VARCHAR(20)   | PENDING / PAID / SHIPPED ...   |
| total_amount | DECIMAL(12,2) | order total                    |
| created_at   | DATETIME      | order time                     |

## order_items

| column      | type          | notes               |
|-------------|---------------|---------------------|
| id          | BIGINT PK     | surrogate key       |
| order_id    | BIGINT FK     | -> orders.id        |
| product_id  | BIGINT FK     | -> products.id      |
| quantity    | INT           | units ordered       |
| unit_price  | DECIMAL(10,2) | price at order time |

## Relationships

- `customers 1—* orders` via `orders.customer_id`
- `orders 1—* order_items` via `order_items.order_id`
- `products 1—* order_items` via `order_items.product_id`
