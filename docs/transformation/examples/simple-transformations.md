# Simple Transformations

**Navigation**: [Documentation Hub](../../index.md) > [Transformation](../index.md) > [Examples](simple-transformations.md) > Simple Transformations

Basic transformation examples to get started.

## Basic Property Mapping

```java
@TransformationContext(source = Person.class, target = PersonDTO.class)
public class PersonTransformations {
    
    @TransformRule(name = "Person2PersonDTO")
    public TransformFunction<Person, PersonDTO> person2PersonDTO() {
        return (person, ctx) -> {
            PersonDTO dto = ctx.createTarget(PersonDTO.class);
            dto.setFirstName(person.getFirstName());
            dto.setLastName(person.getLastName());
            dto.setEmail(person.getEmail());
            return dto;
        };
    }
}
```

## Property Transformation

```java
@TransformRule(name = "Person2PersonDTO")
public TransformFunction<Person, PersonDTO> person2PersonDTO() {
    return (person, ctx) -> {
        PersonDTO dto = ctx.createTarget(PersonDTO.class);
        
        // Concatenate properties
        dto.setFullName(person.getFirstName() + " " + person.getLastName());
        
        // Transform property value
        dto.setEmailLower(person.getEmail().toLowerCase());
        
        // Compute derived property
        dto.setAge(calculateAge(person.getBirthDate()));
        
        return dto;
    };
}
```

## Handling Optional Properties

```java
@TransformRule(name = "Person2PersonDTO")
public TransformFunction<Person, PersonDTO> person2PersonDTO() {
    return (person, ctx) -> {
        PersonDTO dto = ctx.createTarget(PersonDTO.class);
        dto.setName(person.getName());
        
        // Handle optional reference
        if (person.getAddress() != null) {
            dto.setAddress(ctx.equivalent(person.getAddress(), AddressDTO.class));
        }
        
        // Handle optional with default
        dto.setPhoneNumber(
            person.getPhone() != null ? person.getPhone() : "N/A"
        );
        
        return dto;
    };
}
```

## Collection Transformation

```java
@TransformRule(name = "Order2OrderDTO")
public TransformFunction<Order, OrderDTO> order2OrderDTO() {
    return (order, ctx) -> {
        OrderDTO dto = ctx.createTarget(OrderDTO.class);
        dto.setOrderNumber(order.getNumber());
        
        // Transform collection
        for (OrderItem item : order.getItems()) {
            OrderItemDTO itemDTO = ctx.equivalent(item, OrderItemDTO.class);
            dto.getItems().add(itemDTO);
        }
        
        return dto;
    };
}

@TransformRule(name = "OrderItem2OrderItemDTO")
public TransformFunction<OrderItem, OrderItemDTO> orderItem2OrderItemDTO() {
    return (item, ctx) -> {
        OrderItemDTO dto = ctx.createTarget(OrderItemDTO.class);
        dto.setProductName(item.getProduct().getName());
        dto.setQuantity(item.getQuantity());
        dto.setPrice(item.getPrice());
        return dto;
    };
}
```

## Filtered Collection

```java
@TransformRule(name = "Company2CompanyDTO")
public TransformFunction<Company, CompanyDTO> company2CompanyDTO() {
    return (company, ctx) -> {
        CompanyDTO dto = ctx.createTarget(CompanyDTO.class);
        dto.setName(company.getName());
        
        // Only transform active employees
        company.getEmployees().stream()
            .filter(Employee::isActive)
            .map(emp -> ctx.equivalent(emp, EmployeeDTO.class))
            .forEach(empDTO -> dto.getEmployees().add(empDTO));
        
        return dto;
    };
}
```

---

**Next**: [Entity Transformations](entity-transformations.md)
