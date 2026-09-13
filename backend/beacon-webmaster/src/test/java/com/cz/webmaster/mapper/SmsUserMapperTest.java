package com.cz.webmaster.mapper;

import com.cz.webmaster.entity.SmsUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import jakarta.annotation.Resource;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@SpringBootTest
public class SmsUserMapperTest {

    @Resource
    private SmsUserMapper smsUserMapper;

    @Test
    public void findById(){
        SmsUser smsUser = smsUserMapper.selectByPrimaryKey(1);
        System.out.println(smsUser);
    }

}