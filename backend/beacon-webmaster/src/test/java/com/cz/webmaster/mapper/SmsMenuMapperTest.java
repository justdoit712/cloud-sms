package com.cz.webmaster.mapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@SpringBootTest
public class SmsMenuMapperTest {

    @Autowired
    private SmsMenuMapper smsMenuMapper;
    @Test
    public void findMenuByUserId() {

        List<Map<String, Object>> list =   smsMenuMapper.findMenuByUserId(1);
        for (Map<String, Object> stringObjectMap : list) {
            System.out.println(stringObjectMap);
        }
    }
}