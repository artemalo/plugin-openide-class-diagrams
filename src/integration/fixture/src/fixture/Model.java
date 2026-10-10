package fixture;

import java.util.List;

interface Repository<T> { T find(int id); }
abstract class Base { public abstract int size(); }
class Customer {
    private String name;
    static class Note { String text; }
}
enum Status { NEW, PAID }
record Address(String city, String street) {}
class Order {
    private Customer customer;
    private Status status;
    private Address address;
    Order(Customer customer) { this.customer = customer; }
}
class OrderRepository extends Base implements Repository<Order> {
    private List<Order> orders;
    private Customer[] customers;
    public Order find(int id) { return orders.get(id); }
    public int size() { return orders.size(); }
}
class Runner {
    public static void main(String[] args) {
        OrderRepository repository = new OrderRepository();
        System.out.println(repository.size());
    }
}
