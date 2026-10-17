package pl.szafka.cosmetics;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class SzafkaCosmetics extends JavaPlugin implements Listener, CommandExecutor {
    private File dataFile;
    private YamlConfiguration data;
    private final Map<UUID, ItemDisplay> costumeDisplays = new HashMap<>();
    private final Map<UUID, ItemDisplay> petDisplays = new HashMap<>();
    private final Map<UUID, BukkitTask> followTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> costumeTasks = new HashMap<>();
    private final NamespacedKey menuKey = new NamespacedKey(this, "menu");
    private final NamespacedKey itemKey = new NamespacedKey(this, "cosmetic");
    private final NamespacedKey kindKey = new NamespacedKey(this, "kind");

    @Override public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "players.yml");
        if (!dataFile.exists()) {
            try { getDataFolder().mkdirs(); dataFile.createNewFile(); }
            catch (IOException e) { getLogger().severe("Nie można utworzyć players.yml: " + e.getMessage()); }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
        for (String cmd : List.of("szafka", "kostiumy", "pety", "kosmetyki")) {
            Objects.requireNonNull(getCommand(cmd)).setExecutor(this);
        }
        getServer().getPluginManager().registerEvents(this, this);
        long period = Math.max(1, getConfig().getLong("settings.pet-follow-period-ticks", 2));
        getServer().getScheduler().runTaskTimer(this, this::refreshEffects, 20L, Math.max(20L, getConfig().getLong("settings.effect-refresh-ticks", 40)));
        getLogger().info("SzafkaSkyPvP uruchomiony.");
    }

    @Override public void onDisable() {
        followTasks.values().forEach(BukkitTask::cancel);
        costumeTasks.values().forEach(BukkitTask::cancel);
        costumeDisplays.values().forEach(Entity::remove);
        petDisplays.values().forEach(Entity::remove);
        saveData();
    }

    private void saveData() {
        try { data.save(dataFile); } catch (IOException e) { getLogger().severe("Nie zapisano players.yml: " + e.getMessage()); }
    }
    private String path(Player p) { return "players." + p.getUniqueId(); }
    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s); }
    private String nice(String s) { return color(s); }
    private long now() { return System.currentTimeMillis(); }

    private ConfigurationSection section(String kind) { return getConfig().getConfigurationSection(kind.equals("kostium") ? "costumes" : "pets"); }
    private Set<String> definitions(String kind) {
        ConfigurationSection s = section(kind);
        return s == null ? Collections.emptySet() : s.getKeys(false);
    }
    private String base(String kind, String id) { return (kind.equals("kostium") ? "costumes." : "pets.") + id + "."; }
    private String itemName(String kind, String id) { return color(getConfig().getString(base(kind,id) + "name", id)); }
    private Material material(String kind, String id) {
        Material m = Material.matchMaterial(getConfig().getString(base(kind,id) + "material", kind.equals("kostium") ? "PLAYER_HEAD" : "PAPER"));
        return m == null ? Material.PAPER : m;
    }
    private long duration(String kind, String id) { return Math.max(1, getConfig().getLong(base(kind,id)+"duration-days", 7)) * 86400000L; }
    private long expiry(Player p, String kind, String id) { return data.getLong(path(p)+".owned."+kind+"."+id, 0L); }
    private boolean owns(Player p, String kind, String id) { return expiry(p,kind,id) > now(); }
    private List<String> owned(Player p, String kind) {
        List<String> out = new ArrayList<>();
        for (String id : definitions(kind)) if (owns(p,kind,id)) out.add(id);
        return out;
    }
    private String equipped(Player p, String kind) { return data.getString(path(p)+".equipped."+kind, ""); }
    private void equip(Player p, String kind, String id) {
        data.set(path(p)+".equipped."+kind, id);
        saveData();
        if (kind.equals("kostium")) updateCostume(p); else updatePet(p);
        p.sendTitle("", color("&aZałożono " + (kind.equals("kostium") ? "kostium " : "peta ") + ChatColor.stripColor(itemName(kind,id))), 5, 40, 10);
    }
    private void unequip(Player p, String kind) {
        String oldId = equipped(p, kind);
        if (oldId == null || oldId.isBlank()) {
            p.sendTitle("", color("&cNie masz założonego " + (kind.equals("kostium") ? "kostiumu" : "peta")), 5, 30, 10);
            return;
        }
        String oldName = ChatColor.stripColor(itemName(kind, oldId));
        data.set(path(p)+".equipped."+kind, "");
        saveData();
        if (kind.equals("kostium")) {
            removeDisplay(costumeDisplays, p.getUniqueId());
            BukkitTask task = costumeTasks.remove(p.getUniqueId()); if (task != null) task.cancel();
        } else {
            removeDisplay(petDisplays,p.getUniqueId());
            BukkitTask t=followTasks.remove(p.getUniqueId()); if(t!=null)t.cancel();
        }
        p.sendTitle("", color("&cZdjąłeś " + (kind.equals("kostium") ? "kostium " : "peta ") + oldName), 5, 40, 10);
    }
    private void removeDisplay(Map<UUID, ItemDisplay> map, UUID id) {
        ItemDisplay e = map.remove(id); if (e != null && e.isValid()) e.remove();
    }
    private ItemStack cosmeticItem(String kind, String id, Player p) {
        ItemStack item = new ItemStack(material(kind,id));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(itemName(kind,id));
        List<String> lore = new ArrayList<>();
        for (String line : getConfig().getStringList(base(kind,id)+"description")) lore.add(color(line));
        lore.add(color("&7Czas: &f" + (owns(p,kind,id) ? remaining(expiry(p,kind,id)-now()) : "nieposiadany")));
        lore.add(color(owns(p,kind,id) ? "&aKliknij, aby założyć" : "&cNie posiadasz tego przedmiotu"));
        List<String> effects = getConfig().getStringList(base(kind,id)+"effects");
        if (!effects.isEmpty()) {
            lore.add(color("&bEfekty:"));
            for (String effect : effects) lore.add(color("&7• " + effect.replace(':',' ')));
        }
        meta.setLore(lore);
        int cmd = getConfig().getInt(base(kind,id)+"custom-model-data",0);
        if (cmd > 0) meta.setCustomModelData(cmd);
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, id);
        meta.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, kind);
        item.setItemMeta(meta);
        return item;
    }
    private String remaining(long ms) {
        if (ms <= 0) return "wygasł";
        long days=ms/86400000L, hours=(ms%86400000L)/3600000L, mins=(ms%3600000L)/60000L;
        if(days>0)return days+"d "+hours+"h";
        if(hours>0)return hours+"h "+mins+"min";
        return Math.max(1,mins)+"min";
    }
    private ItemStack button(Material mat, String name, String menu) {
        ItemStack i = new ItemStack(mat); ItemMeta m=i.getItemMeta(); m.setDisplayName(color(name));
        m.getPersistentDataContainer().set(menuKey,PersistentDataType.STRING,menu); i.setItemMeta(m); return i;
    }
    private Inventory createMenu(String title) { return Bukkit.createInventory(null,54,color(title)); }
    private void fill(Inventory inv) {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m=pane.getItemMeta(); m.setDisplayName(" "); pane.setItemMeta(m);
        for(int i=0;i<54;i++) if(inv.getItem(i)==null) inv.setItem(i,pane);
    }
    private void openLocker(Player p) {
        Inventory inv=createMenu("&8✦ Twoja szafka ✦");
        inv.setItem(20, equippedItem(p,"kostium"));
        inv.setItem(24, equippedItem(p,"pet"));
        inv.setItem(29,button(Material.BARRIER,"&cZdejmij kostium","unequip:kostium"));
        inv.setItem(33,button(Material.BARRIER,"&cZdejmij peta","unequip:pet"));
        inv.setItem(45,button(Material.LEATHER_CHESTPLATE,"&dKOSTIUMY","list:kostium:1"));
        inv.setItem(49,button(Material.CLOCK,"&ePrzedłużenia / sklep","shop"));
        inv.setItem(53,button(Material.BEE_SPAWN_EGG,"&aPETY","list:pet:1"));
        inv.setItem(50,button(Material.BARRIER,"&cZamknij","close"));
        fill(inv); p.openInventory(inv);
    }
    private ItemStack equippedItem(Player p, String kind) {
        String id=equipped(p,kind);
        if(id==null || id.isBlank() || !owns(p,kind,id)) return button(Material.GRAY_DYE, kind.equals("kostium")?"&7Brak kostiumu":"&7Brak peta", "noop");
        return cosmeticItem(kind,id,p);
    }
    private void openList(Player p, String kind, int page) {
        List<String> ids=new ArrayList<>(definitions(kind));
        Inventory inv=createMenu(kind.equals("kostium")?"&8Kostiumy — strona "+page:"&8Pety — strona "+page);
        int start=(page-1)*45;
        for(int n=0;n<45 && start+n<ids.size();n++) inv.setItem(n,cosmeticItem(kind,ids.get(start+n),p));
        if(page>1) inv.setItem(45,button(Material.ARROW,"&ePoprzednia strona","list:"+kind+":"+(page-1)));
        inv.setItem(49,button(Material.BARRIER,"&cPowrót do szafki","locker"));
        if(start+45<ids.size()) inv.setItem(53,button(Material.ARROW,"&eNastępna strona","list:"+kind+":"+(page+1)));
        if(ids.isEmpty()) inv.setItem(22,button(Material.BARRIER,"&cNie masz żadnych przedmiotów","noop"));
        p.openInventory(inv);
    }
    private void openShop(Player p, String kind) {
        Inventory inv=createMenu(kind.equals("kostium")?"&8Sklep kostiumów":"&8Sklep petów");
        for(String id:definitions(kind)) {
            int slot=inv.firstEmpty();
            if(slot<0 || slot>=45) break;
            ItemStack it=new ItemStack(material(kind,id)); ItemMeta m=it.getItemMeta();
            m.setDisplayName(itemName(kind,id));
            List<String> lore=new ArrayList<>();
            lore.add(color("&7Cena: &e"+getConfig().getInt(base(kind,id)+"price",0)+" monet"));
            lore.add(color("&7Czas: &f"+getConfig().getInt(base(kind,id)+"duration-days",7)+" dni"));
            lore.add(color("&aKliknij, aby kupić"));
            m.setLore(lore); m.getPersistentDataContainer().set(itemKey,PersistentDataType.STRING,id);
            m.getPersistentDataContainer().set(kindKey,PersistentDataType.STRING,kind); it.setItemMeta(m); inv.setItem(slot,it);
        }
        inv.setItem(49,button(Material.BARRIER,"&cPowrót","locker")); p.openInventory(inv);
    }
    private void openCrate(Player p, String kind) {
        Inventory inv=createMenu(kind.equals("kostium")?"&8Skrzynka kostiumów":"&8Skrzynka petów");
        inv.setItem(22,button(Material.CHEST,"&6Otwórz skrzynkę (&e100 monet&6)","crate-open:"+kind));
        inv.setItem(49,button(Material.BARRIER,"&cPowrót","locker")); p.openInventory(inv);
    }
    private int coins(Player p) { return data.getInt(path(p)+".coins",getConfig().getInt("settings.starting-coins",0)); }
    private void setCoins(Player p,int amount) { data.set(path(p)+".coins",Math.max(0,amount)); saveData(); }
    private boolean purchase(Player p,String kind,String id) {
        int price=getConfig().getInt(base(kind,id)+"price",0);
        if(owns(p,kind,id)){p.sendMessage(color("&cJuż posiadasz ten przedmiot."));return false;}
        if(coins(p)<price){p.sendMessage(color("&cNie masz wystarczająco monet. Saldo: "+coins(p)));return false;}
        setCoins(p,coins(p)-price);
        data.set(path(p)+".owned."+kind+"."+id,now()+duration(kind,id)); saveData();
        p.sendMessage(color("&aKupiono "+itemName(kind,id)+" &aza "+price+" monet!"));
        return true;
    }
    private void grant(Player p,String kind,String id,int days) {
        if(!definitions(kind).contains(id)){p.sendMessage(color("&cNie ma takiego przedmiotu: "+id));return;}
        long old=expiry(p,kind,id);
        data.set(path(p)+".owned."+kind+"."+id,Math.max(now(),old)+Math.max(1,days)*86400000L); saveData();
        p.sendMessage(color("&aOtrzymano "+itemName(kind,id)+" &ana "+days+" dni."));
    }
    private void updateCostume(Player p) {
        removeDisplay(costumeDisplays,p.getUniqueId());
        BukkitTask previous=costumeTasks.remove(p.getUniqueId()); if(previous!=null) previous.cancel();
        String id=equipped(p,"kostium");
        if(id==null || id.isBlank() || !owns(p,"kostium",id)) return;
        ItemDisplay d=p.getWorld().spawn(p.getLocation().add(0,2.05,0),ItemDisplay.class, e->{
            e.setItemStack(new ItemStack(material("kostium",id))); e.setBillboard(Display.Billboard.CENTER);
            e.setPersistent(false); e.setInvulnerable(true); e.setGravity(false);
        });
        costumeDisplays.put(p.getUniqueId(),d);
        // Cosmetic display is a floating head/item above the player; a full-body model requires a resource pack.
        costumeTasks.put(p.getUniqueId(), Bukkit.getScheduler().runTaskTimer(this, ()->{
            if(!p.isOnline() || !d.isValid() || !id.equals(equipped(p,"kostium")) || !owns(p,"kostium",id)) {
                if(d.isValid()) d.remove();
                BukkitTask task=costumeTasks.remove(p.getUniqueId()); if(task!=null) task.cancel();
                return;
            }
            d.teleport(p.getLocation().add(0,2.05,0));
        },1L,2L));
    }
    private void updatePet(Player p) {
        removeDisplay(petDisplays,p.getUniqueId());
        BukkitTask old=followTasks.remove(p.getUniqueId()); if(old!=null)old.cancel();
        String id=equipped(p,"pet");
        if(id==null || id.isBlank() || !owns(p,"pet",id)) return;
        ItemDisplay d=p.getWorld().spawn(p.getLocation().add(0,1.85,0),ItemDisplay.class,e->{
            e.setItemStack(new ItemStack(material("pet",id))); e.setBillboard(Display.Billboard.CENTER);
            e.setPersistent(false); e.setInvulnerable(true); e.setGravity(false);
        });
        petDisplays.put(p.getUniqueId(),d);
        followTasks.put(p.getUniqueId(),Bukkit.getScheduler().runTaskTimer(this,()->{
            if(!p.isOnline() || !d.isValid() || !id.equals(equipped(p,"pet")) || !owns(p,"pet",id)) {
                d.remove(); BukkitTask t=followTasks.remove(p.getUniqueId()); if(t!=null)t.cancel(); return;
            }
            Location loc=p.getLocation(); double a=Math.toRadians(loc.getYaw()+35);
            double dist=getConfig().getDouble("settings.pet-distance",0.85);
            Location target=loc.clone().add(-Math.sin(a)*dist,getConfig().getDouble("settings.pet-height",1.85),Math.cos(a)*dist);
            if(!d.getWorld().equals(target.getWorld())) d.teleport(target); else d.teleport(target);
        },1L,Math.max(1,getConfig().getLong("settings.pet-follow-period-ticks",2))));
    }
    private void refreshEffects() {
        for(Player p:Bukkit.getOnlinePlayers()) {
            for(String kind:List.of("kostium","pet")) {
                String id=equipped(p,kind);
                if(id==null || id.isBlank()) continue;
                if(!owns(p,kind,id)){unequip(p,kind);continue;}
                for(String raw:getConfig().getStringList(base(kind,id)+"effects")) {
                    String[] parts=raw.split(":");
                    PotionEffectType type=PotionEffectType.getByName(parts[0].trim().toUpperCase(Locale.ROOT));
                    int amp=parts.length>1?parseInt(parts[1],0):0;
                    if(type!=null)p.addPotionEffect(new PotionEffect(type,80,Math.max(0,amp),true,false,false));
                }
            }
        }
    }
    private int parseInt(String s,int def){try{return Integer.parseInt(s);}catch(Exception e){return def;}}
    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p=e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this,()->{updateCostume(p);updatePet(p);},10L);
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        UUID id=e.getPlayer().getUniqueId();
        removeDisplay(costumeDisplays,id); removeDisplay(petDisplays,id);
        BukkitTask t=followTasks.remove(id);if(t!=null)t.cancel();
        BukkitTask ct=costumeTasks.remove(id);if(ct!=null)ct.cancel();
    }
    @EventHandler public void onClick(InventoryClickEvent e) {
        if(!(e.getWhoClicked() instanceof Player p))return;
        String title=e.getView().getTitle();
        if(!title.startsWith(color("&8✦ Twoja szafka")) && !title.startsWith(color("&8Kostiumy")) &&
           !title.startsWith(color("&8Pety")) && !title.startsWith(color("&8Sklep")) &&
           !title.startsWith(color("&8Skrzynka"))) return;
        e.setCancelled(true);
        ItemStack clicked=e.getCurrentItem(); if(clicked==null || !clicked.hasItemMeta())return;
        ItemMeta meta=clicked.getItemMeta();
        String action=meta.getPersistentDataContainer().get(menuKey,PersistentDataType.STRING);
        String id=meta.getPersistentDataContainer().get(itemKey,PersistentDataType.STRING);
        String kind=meta.getPersistentDataContainer().get(kindKey,PersistentDataType.STRING);
        if(action!=null) {
            if(action.equals("close")){p.closeInventory();return;}
            if(action.equals("locker")){openLocker(p);return;}
            if(action.equals("noop"))return;
            if(action.startsWith("unequip:")){unequip(p,action.substring(8));openLocker(p);return;}
            if(action.startsWith("list:")){String[] a=action.split(":");openList(p,a[1],parseInt(a[2],1));return;}
            if(action.equals("shop")){Inventory inv=createMenu("&8Sklep i skrzynki");inv.setItem(20,button(Material.LEATHER_CHESTPLATE,"&dSklep kostiumów","shop-costumes"));inv.setItem(24,button(Material.BEE_SPAWN_EGG,"&aSklep petów","shop-pets"));inv.setItem(29,button(Material.CHEST,"&6Skrzynka kostiumów","crate-costumes"));inv.setItem(33,button(Material.CHEST,"&6Skrzynka petów","crate-pets"));inv.setItem(49,button(Material.BARRIER,"&cPowrót","locker"));p.openInventory(inv);return;}
            if(action.equals("shop-costumes")){openShop(p,"kostium");return;}
            if(action.equals("shop-pets")){openShop(p,"pet");return;}
            if(action.equals("crate-costumes")){openCrate(p,"kostium");return;}
            if(action.equals("crate-pets")){openCrate(p,"pet");return;}
            if(action.startsWith("crate-open:")){String k=action.substring(11);if(coins(p)<100){p.sendMessage(color("&cPotrzebujesz 100 monet."));return;}List<String> ids=new ArrayList<>(definitions(k));if(ids.isEmpty())return;setCoins(p,coins(p)-100);String won=ids.get(ThreadLocalRandom.current().nextInt(ids.size()));grant(p,k,won,getConfig().getInt(base(k,won)+"duration-days",7));p.sendMessage(color("&6Wylosowano: ")+itemName(k,won));openLocker(p);return;}
        }
        if(id!=null && kind!=null) {
            if(title.startsWith(color("&8Sklep"))) {if(purchase(p,kind,id)){openShop(p,kind);}return;}
            if(owns(p,kind,id)){equip(p,kind,id);openLocker(p);}
            else p.sendMessage(color("&cNie posiadasz tego przedmiotu. Kup go w sklepie lub poproś administratora."));
        }
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if(command.getName().equalsIgnoreCase("szafka") || command.getName().equalsIgnoreCase("kostiumy") || command.getName().equalsIgnoreCase("pety")) {
            if(!(sender instanceof Player p)){sender.sendMessage("Ta komenda jest dla gracza.");return true;}
            if(command.getName().equalsIgnoreCase("szafka"))openLocker(p);
            else openList(p,command.getName().equalsIgnoreCase("kostiumy")?"kostium":"pet",1);
            return true;
        }
        if(args.length==0){sender.sendMessage(color("&e/kosmetyki give <gracz> <kostium|pet> <id> [dni]"));sender.sendMessage(color("&e/kosmetyki extend <gracz> <kostium|pet> <id> <dni>"));sender.sendMessage(color("&e/kosmetyki coins <gracz> <kwota>"));sender.sendMessage(color("&e/kosmetyki crate <gracz> <kostium|pet>"));sender.sendMessage(color("&e/kosmetyki reload"));return true;}
        if(!sender.hasPermission("szafka.admin")){sender.sendMessage(color("&cBrak uprawnień."));return true;}
        if(args[0].equalsIgnoreCase("reload")){reloadConfig();sender.sendMessage(color("&aPrzeładowano config.yml."));return true;}
        if(args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("extend")) {
            if(args.length<5){sender.sendMessage(color("&cUżycie: /kosmetyki "+args[0]+" <gracz> <kostium|pet> <id> <dni>"));return true;}
            Player target=Bukkit.getPlayerExact(args[1]);if(target==null){sender.sendMessage(color("&cGracz musi być online."));return true;}
            String kind=args[2].equalsIgnoreCase("kostium")?"kostium":"pet";int days=parseInt(args[4],7);
            grant(target,kind,args[3],days);sender.sendMessage(color("&aNadano/przedłużono przedmiot."));
            if(args[0].equalsIgnoreCase("extend") && equipped(target,kind).equals(args[3])){if(kind.equals("kostium"))updateCostume(target);else updatePet(target);}
            return true;
        }
        if(args[0].equalsIgnoreCase("coins")) {
            if(args.length<3){sender.sendMessage(color("&c/kosmetyki coins <gracz> <kwota>"));return true;}
            Player target=Bukkit.getPlayerExact(args[1]);if(target==null){sender.sendMessage(color("&cGracz musi być online."));return true;}
            setCoins(target,parseInt(args[2],0));sender.sendMessage(color("&aUstawiono saldo gracza na "+coins(target)+" monet."));return true;
        }
        if(args[0].equalsIgnoreCase("crate")) {
            if(args.length<3){sender.sendMessage(color("&c/kosmetyki crate <gracz> <kostium|pet>"));return true;}
            Player target=Bukkit.getPlayerExact(args[1]);if(target==null){sender.sendMessage(color("&cGracz musi być online."));return true;}
            String kind=args[2].equalsIgnoreCase("kostium")?"kostium":"pet";List<String> ids=new ArrayList<>(definitions(kind));
            if(ids.isEmpty()){sender.sendMessage(color("&cBrak wpisów w config.yml."));return true;}
            String won=ids.get(ThreadLocalRandom.current().nextInt(ids.size()));grant(target,kind,won,getConfig().getInt(base(kind,won)+"duration-days",7));
            sender.sendMessage(color("&aGracz wylosował: ")+itemName(kind,won));return true;
        }
        sender.sendMessage(color("&cNieznana komenda."));return true;
    }
}
