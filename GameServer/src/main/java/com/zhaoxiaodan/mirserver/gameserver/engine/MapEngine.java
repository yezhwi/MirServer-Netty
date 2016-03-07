package com.zhaoxiaodan.mirserver.gameserver.engine;

import com.zhaoxiaodan.mirserver.db.objects.BaseObject;
import com.zhaoxiaodan.mirserver.db.types.MapPoint;
import com.zhaoxiaodan.mirserver.utils.ConfigFileLoader;
import com.zhaoxiaodan.mirserver.utils.NumUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MapEngine {

	private static Logger logger = LogManager.getLogger();

	private static final String MAP_CONFIG_FILE        = "Envir/MapInfo.cfg";
	private static final String MINIMAP_CONFIG_FILE    = "Envir/MiniMap.cfg";
	private static final String STARTPOINT_CONFIG_FILE = "Envir/StartPoint.cfg";
	private static final String MAP_FILES_DIR          = "Envir/Maps";

	/**
	 * 地图文件头大小
	 */
	private static final int MAP_FILE_HEADER_SIZE = 52;
	private static final int MAP_FILE_TILE_SIZE   = 12;

	private static Map<String, MapInfo> mapList    = null;
	private static MapPoint             startPoint = null;

	public static class Tile {

		public boolean canWalk;
		public final Map<Integer, BaseObject> objects = new ConcurrentHashMap<>();
	}

	public static class MapInfo {

		public short width;
		public short height;

		public String mapId;
		/**
		 * 地图文件里的地图标题
		 */
		public String mapTitle;
		/**
		 * 自己配置的地图名
		 */
		public String mapDescription;
		public String miniMapId;

		/**
		 * 服务器只关心当前格子是否能走
		 */
		public Tile[][] tiles;
		//空间换时间, 方便取地图上可以行走的随机点
		public List<Integer> allCanWalkTileXY = new ArrayList<>();

		public void putObject(BaseObject object){
			if(object.currMapPoint.x >= this.width ||
					object.currMapPoint.y >= this.height)
				return;

			this.tiles[object.currMapPoint.x][object.currMapPoint.y].objects.put(object.inGameId,object);
		}

		public void removeObject(BaseObject object){
			if(object.currMapPoint.x >= this.width ||
					object.currMapPoint.y >= this.height)
				return;

			this.tiles[object.currMapPoint.x][object.currMapPoint.y].objects.remove(object.inGameId);
		}

		public void loadMapFile() throws Exception {

			FileInputStream in = null;
			try {
				String filename = MAP_FILES_DIR + "/" + mapId + ".map";
				try {
					in = new FileInputStream(filename);
				} catch (FileNotFoundException e) {
					throw new FileNotFoundException("地图文件找不到: " + filename);
				}
				byte[] buf = new byte[MAP_FILE_HEADER_SIZE];
				if (in.read(buf, 0, MAP_FILE_HEADER_SIZE) != MAP_FILE_HEADER_SIZE)
					throw new Exception("地图文件头大小不正确,文件:" + filename);

				width = NumUtil.readShort(buf, 0, true);
				height = NumUtil.readShort(buf, 2, true);
				mapTitle = new String(buf, 4, 16);
				logger.debug("读取文件地图:{} , width:{}, height:{}, title:{}", filename, width, height, mapTitle);
				tiles = new Tile[width][height];


				for (short x = 0; x < width; x++) {
					for (short y = 0; y < height; y++) {
						if (in.read(buf, 0, MAP_FILE_TILE_SIZE) != MAP_FILE_TILE_SIZE)
							throw new Exception("地图文件tile大小不正确,文件,x,y:" + filename + "," + x + "," + y);
						short floorImg = NumUtil.readShort(buf, 0, true);
						//最高位是1 则不能站人
						boolean canWalk = !((floorImg & 0x8000) == 0x8000);
						Tile    tile    = new Tile();
						tile.canWalk = canWalk;
						tiles[x][y] = tile;

						if (canWalk)
							allCanWalkTileXY.add(NumUtil.makeLong(x, y));
					}
				}
			} finally {
				if (null != in)
					in.close();
			}


		}
	}

	public static boolean canWalk(MapPoint mapPoint) {
		MapInfo mapInfo = mapList.get(mapPoint.mapId);
		if (null == mapInfo)
			return false;

		if (mapPoint.x < 0 || mapPoint.x >= mapInfo.width || mapPoint.y < 0 || mapPoint.y >= mapInfo.height)
			return false;

		return mapInfo.tiles[mapPoint.x][mapPoint.y].canWalk;
	}

	public static List<BaseObject> getObjects(MapInfo mapInfo, int startX, int width, int startY, int height) {
		List<BaseObject> objects = new ArrayList<>();
		int endX = startX + width >= mapInfo.width ? mapInfo.width:startX+width;
		int endY = startY + height >= mapInfo.height ? mapInfo.height:startY+height;
		for (int x = startX; x < endX; x++){
			for(int y = startY; y<endY;y++){
				objects.addAll(mapInfo.tiles[x][y].objects.values());
			}
		}

		return objects;
	}


	public static synchronized void reload() throws Exception {
		Map<String, MapInfo> maps = new HashMap<>();
		reloadMapInfo(maps);
		reloadMiniMap(maps);
		MapPoint startPoint = reloadStartPoint(maps);

		// 保证读出来的无异常再替换原有的;
		MapEngine.mapList = maps;
		MapEngine.startPoint = startPoint;
	}

	private static void reloadMapInfo(Map<String, MapInfo> maps) throws Exception {

		for (StringTokenizer tokenizer : ConfigFileLoader.load(MAP_CONFIG_FILE, 2)) {

			MapInfo info = new MapInfo();
			info.mapId = (String) tokenizer.nextElement();
			info.mapDescription = (String) tokenizer.nextElement();

			if (maps.containsKey(info.mapId)) {
				throw new Exception("地图名 " + info.mapId + " 的配置已经存在, 检查是否重复.");
			}

			info.loadMapFile();

			maps.put(info.mapId, info);
		}
	}

	private static void reloadMiniMap(Map<String, MapInfo> maps) throws Exception {
		for (StringTokenizer tokenizer : ConfigFileLoader.load(MINIMAP_CONFIG_FILE, 2)) {
			String fileName = (String) tokenizer.nextElement();
			if (!maps.containsKey(fileName))
				throw new Exception("小地图对应的地图" + fileName + " 在地图配置中不存在, 先在地图配置文件" + MAP_CONFIG_FILE + "中添加");

			maps.get(fileName).miniMapId = (String) tokenizer.nextElement();
		}
	}

	private static MapPoint reloadStartPoint(Map<String, MapInfo> maps) throws Exception {
		MapPoint startPoint = null;
		for (StringTokenizer tokenizer : ConfigFileLoader.load(STARTPOINT_CONFIG_FILE, 2)) {
			startPoint = new MapPoint();
			startPoint.mapId = (String) tokenizer.nextElement();
			if (!maps.containsKey(startPoint.mapId))
				throw new Exception("出生点所在地图" + startPoint.mapId + "在地图配置中不存在, 先在地图配置文件" + MAP_CONFIG_FILE + "中添加");

			startPoint.x = Short.parseShort((String) tokenizer.nextElement());
			startPoint.y = Short.parseShort((String) tokenizer.nextElement());

			break;
		}
		if (null == startPoint)
			throw new Exception("还没配置出生点, 在" + STARTPOINT_CONFIG_FILE + " 中配置");
		return startPoint;
	}

	public static MapInfo getMapInfo(String mapId) {
		return mapList.get(mapId);
	}

	public static MapPoint getStartPoint() {
		return startPoint;
	}
}
