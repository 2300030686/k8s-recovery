package com.demo.recovery;
import jakarta.persistence.*;
import java.math.BigDecimal;
@Entity @Table(name="customers")
public class Customer {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Integer id;
  private String name, email, city;
  private BigDecimal amount;
  public Integer getId(){return id;} public void setId(Integer v){id=v;}
  public String getName(){return name;} public void setName(String v){name=v;}
  public String getEmail(){return email;} public void setEmail(String v){email=v;}
  public String getCity(){return city;} public void setCity(String v){city=v;}
  public BigDecimal getAmount(){return amount;} public void setAmount(BigDecimal v){amount=v;}
}
