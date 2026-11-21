// Shared work → track → version hierarchy. Paths are relative to index.html.
window.ARMUSIC_WORKS = [
  {
    id: "night-rail", title: "夜行支线", genre: "动画短片", cover: "assets/rail.jpg",
    description: "末班列车穿过山谷，车窗里只剩稀疏的灯。两个版本保留了同一段旋律，可以在同一小节来回听。",
    tracks: [{ id: "last-train", title: "末班车经过山谷", versions: [
      { id: "felt", title: "毡音键盘", src: "assets/last-train.wav" },
      { id: "bells", title: "高八度钟琴", src: "assets/last-train-bells.wav" }
    ] }]
  },
  {
    id: "tide-atlas", title: "退潮图志", genre: "探索游戏", cover: "assets/sail.jpg",
    description: "潮水退去，小船沿着裸露的礁石驶入浅湾。低音托住慢慢展开的簧管旋律，像在海图上画下一条航线。",
    tracks: [{ id: "ebb", title: "浅湾航线", versions: [
      { id: "reed", title: "簧管小调", src: "assets/ebb.wav" }
    ] }]
  }
];
