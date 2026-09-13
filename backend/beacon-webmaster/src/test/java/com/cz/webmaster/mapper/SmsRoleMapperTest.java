package com.cz.webmaster.mapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@SpringBootTest
public class SmsRoleMapperTest {

    @Autowired
    private SmsRoleMapper smsRoleMapper;
    @Test
    public void findRoleNameByUserId() {
        Set<String> set =  smsRoleMapper.findRoleNameByUserId(1);
        System.out.println(set);
    }
}