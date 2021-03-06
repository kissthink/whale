package org.octopus.iot.service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.nutz.dao.Cnd;
import org.nutz.dao.Dao;
import org.nutz.dao.util.Daos;
import org.nutz.ioc.loader.annotation.Inject;
import org.nutz.ioc.loader.annotation.IocBean;
import org.nutz.json.Json;
import org.nutz.json.JsonFormat;
import org.nutz.lang.Files;
import org.nutz.lang.Lang;
import org.nutz.lang.Strings;
import org.octopus.iot.Iots;
import org.octopus.iot.bean.IotSensor;
import org.octopus.iot.bean.IotSensorTrigger;
import org.octopus.iot.bean.IotSensorUpdateRule;
import org.octopus.iot.bean.SensorUploadResult;
import org.octopus.iot.bean.history.IotImageHistory;
import org.octopus.iot.bean.history.IotKvHistory;
import org.octopus.iot.bean.history.IotLocationHistory;
import org.octopus.iot.bean.history.IotNumberHistory;
import org.octopus.iot.bean.history.IotOnoffHistory;
import org.octopus.iot.mqtt.MqttService;

@IocBean(create="init")
public class IotSensorService {
	
	@Inject Dao dao;
	
	@Inject MqttService mqttService;
	
	@Inject("java:$conf.get('photo.path')") String photoPath;

	@SuppressWarnings("unchecked")
	public SensorUploadResult upload(IotSensor sensor, InputStream in) throws IOException {
		SensorUploadResult re = new SensorUploadResult();
		Object data;
		try {
			data = Json.fromJson(new InputStreamReader(in));
		} catch (Throwable e) {
			re.err = "Bad json";
			return re;
		}
		if (data == null) {
			re.err = "NULL json";
			return re;
		}
		switch (sensor.getType()) {
		case number:
		case location:
		case kv:
		case onoff:
			if (data instanceof List) {
				List<Map<String, Object>> list = (List<Map<String, Object>>) data;
				for (Map<String, Object> map : list) {
					updateSensorValue(sensor, map);
				}
				return null;
			} else if (data instanceof Map) {
				String msg = updateSensorValue(sensor, (Map<String, Object>)data);
				if (msg != null) {
					return re;
				}
			} else {
				re.err = "bad data type";
				return re;
			}
			break;
		default:
			re.err = "not updateable";
			return re;
		}
		return re;
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public String updateSensorValue(IotSensor sensor, Map<String, Object> map) {
		Object v = map.get("value");
		Object t = map.get("timestamp");
		Date time = null;
		boolean insertHistory = sensor.getUpdateRule() != IotSensorUpdateRule.contrl;
		if (t == null) {
			time = new Date();
		} else {
			try {
				time = new SimpleDateFormat("yyyyMMdd'T'HH:mm:ss").parse(String.valueOf(t));
			} catch (ParseException e) {
				return "bad timestamp";
			}
		}
		map.put("timestamp", time);
		switch (sensor.getType()) {
		case number:
			double value = Double.NaN;
			try {
				value = ((Number)v).doubleValue();
			} catch (Throwable e) {
				return "bad value";
			}
			if (!insertHistory)
				break;
			IotNumberHistory h = new IotNumberHistory();
			h.setSensorId(sensor.getId());
			h.setValue(value);
			h.setTimestamp(time);
			partDao(sensor).fastInsert(h);
			break;
		case location:
			Map<String, Object> tmp = (Map)v;
			if (!tmp.containsKey("lan") || !tmp.containsKey("lat") || !tmp.containsKey("speed")) {
				return "miss some gps key";
			}
			if (!insertHistory)
				break;
			IotLocationHistory gps = null;
			try {
				gps = Lang.map2Object(tmp, IotLocationHistory.class);
			} catch (Throwable e) {
				return "bad gps data";
			}
			gps.setSensorId(sensor.getId());
			gps.setTimestamp(time);
			partDao(sensor).fastInsert(gps);
			break;
		case kv:
			Map<String, Object> tmp2 = (Map)v;
			String key = (String)tmp2.get("key");
			if (Strings.isBlank(key)) {
				return "key is blank or miss";
			}
			if (!insertHistory)
				break;
			IotKvHistory raw = new IotKvHistory();
			raw.setSensorId(sensor.getId());
			raw.setTimestamp(time);
			raw.setKey(key);
			raw.setValue(Json.toJson(v, JsonFormat.full().setIndent(0)));
			partDao(sensor).fastInsert(raw);
			break;
		case onoff:
			if ("1".equals(String.valueOf(v))) {
				v = 1;
			} else {
				v = 0;
			}
			if (!insertHistory)
				break;
			IotOnoffHistory onoff = new IotOnoffHistory();
			onoff.setTimestamp(time);
			onoff.setValue((Integer)v);
			partDao(sensor).fastInsert(onoff);
			break;
		default:
			break;
		}
		sensor.setLastUpdateTime(new Date());
		sensor.setValue(Json.toJson(map, JsonFormat.compact()));
		partDao(sensor).update(sensor, "^(lastUpdateTime|value)$");
		List<IotSensorTrigger> tirggers = partDao(sensor).query(IotSensorTrigger.class, Cnd.where("sensorId", "=", sensor.getId()));
		for (IotSensorTrigger trigger : tirggers) {
			trigger.trigger(sensor, map, v);
		}
		// publish to mqtt
		mqttService.publish("iot2/sensor/"+sensor.getId(), sensor.getValue());
		return null;
	}
	
	public Dao partDao(IotSensor sensor) {
		long part = sensor.getId() / Iots.PART;
		return Daos.ext(dao, "" + part);
	}
	
	public void saveImage(IotSensor sensor, File tmp, int w, int h) throws IOException {
		IotImageHistory img = new IotImageHistory();
		img.setSensorId(sensor.getId());
		img.setWidth(w);
		img.setHeight(h);
		img.setTimestamp(new Date());
		partDao(sensor).insert(img);
		Files.copy(tmp, new File(String.format("%s/%s/%s", photoPath, sensor.getId(), img.getId())));
		sensor.setLastUpdateTime(new Date());
		partDao(sensor).update(sensor, "^(lastUpdateTime)$");
	}
	
	public void init() {
		Files.makeDir(new File(photoPath));
	}
}
