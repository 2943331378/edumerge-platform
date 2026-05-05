package com.edumerge.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edumerge.entity.CardDeck;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CardDeckMapper extends BaseMapper<CardDeck> {
}
