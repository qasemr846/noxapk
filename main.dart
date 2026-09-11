import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

class Activity {
  final String title, category;
  final int minutes;
  final DateTime date;
  final bool done;
  Activity({required this.title, required this.category, required this.minutes, required this.date, this.done=true});
  Map<String,dynamic> toJson()=>{'title':title,'category':category,'minutes':minutes,'date':date.toIso8601String(),'done':done};
  factory Activity.fromJson(Map<String,dynamic> j)=>Activity(title:j['title'],category:j['category'],minutes:j['minutes'],date:DateTime.parse(j['date']),done:j['done']??true);
}

void main()=>runApp(const Nox());

class Nox extends StatefulWidget { const Nox({super.key}); @override State<Nox> createState()=>_NoxState(); }
class _NoxState extends State<Nox> {
  int tab=0; List<Activity> items=[]; bool loading=true;
  final cats=['کار','یادگیری','ورزش','شخصی','استراحت','سایر'];
  @override void initState(){super.initState(); load();}
  Future<void> load() async { final p=await SharedPreferences.getInstance(); final s=p.getString('activities'); if(s!=null) items=(jsonDecode(s) as List).map((e)=>Activity.fromJson(e)).toList(); setState(()=>loading=false); }
  Future<void> save() async { final p=await SharedPreferences.getInstance(); await p.setString('activities',jsonEncode(items.map((e)=>e.toJson()).toList())); }
  void add() {
    final t=TextEditingController(), m=TextEditingController(text:'30'); String cat='کار';
    showDialog(context:context,builder:(_)=>StatefulBuilder(builder:(c,setD)=>AlertDialog(
      title:const Text('ثبت فعالیت'),
      content:Column(mainAxisSize:MainAxisSize.min,children:[
        TextField(controller:t,decoration:const InputDecoration(labelText:'عنوان فعالیت')),
        const SizedBox(height:8), TextField(controller:m,keyboardType:TextInputType.number,decoration:const InputDecoration(labelText:'مدت (دقیقه)')),
        const SizedBox(height:8), DropdownButtonFormField<String>(value:cat,items:cats.map((x)=>DropdownMenuItem(value:x,child:Text(x))).toList(),onChanged:(x)=>setD(()=>cat=x!),decoration:const InputDecoration(labelText:'دسته‌بندی'))
      ]),
      actions:[TextButton(onPressed:()=>Navigator.pop(c),child:const Text('لغو')),FilledButton(onPressed:(){if(t.text.trim().isNotEmpty){setState(()=>items.add(Activity(title:t.text.trim(),category:cat,minutes:int.tryParse(m.text)??0,date:DateTime.now())));save();}Navigator.pop(c);},child:const Text('ثبت'))],
    )));
  }
  DateTime startOfWeek(DateTime d){final x=DateTime(d.year,d.month,d.day); return x.subtract(Duration(days:x.weekday-1));}
  List<Activity> get week {final s=startOfWeek(DateTime.now()),e=s.add(const Duration(days:7));return items.where((a)=>!a.date.isBefore(s)&&a.date.isBefore(e)).toList();}
  @override Widget build(BuildContext context)=>MaterialApp(debugShowCheckedModeBanner:false,title:'nox',theme:ThemeData(useMaterial3:true,colorSchemeSeed:Colors.indigo),home:loading?const Scaffold(body:Center(child:CircularProgressIndicator())):Scaffold(
    appBar:AppBar(title:const Text('nox'),centerTitle:true),
    body:tab==0?home():tab==1?analysis():calendar(),
    floatingActionButton:tab==0?FloatingActionButton.extended(onPressed:add,icon:const Icon(Icons.add),label:const Text('فعالیت جدید')):null,
    bottomNavigationBar:NavigationBar(selectedIndex:tab,onDestinationSelected:(x)=>setState(()=>tab=x),destinations:const[
      NavigationDestination(icon:Icon(Icons.check_circle_outline),label:'کارها'),
      NavigationDestination(icon:Icon(Icons.analytics_outlined),label:'تحلیل'),
      NavigationDestination(icon:Icon(Icons.calendar_month_outlined),label:'تقویم')]),
  );

  Widget home(){final today=items.where((a)=>DateUtils.isSameDay(a.date,DateTime.now())).toList(); final mins=today.fold(0,(s,a)=>s+a.minutes); return ListView(padding:const EdgeInsets.all(16),children:[
    Card(child:Padding(padding:const EdgeInsets.all(18),child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[Text('امروز',style:Theme.of(context).textTheme.headlineSmall?.copyWith(fontWeight:FontWeight.bold)),const SizedBox(height:8),Text('${today.length} فعالیت  •  $mins دقیقه')])),
    const SizedBox(height:12),
    if(items.isEmpty) const Card(child:Padding(padding:EdgeInsets.all(28),child:Text('هنوز کاری ثبت نکردی.\nاولین فعالیتت را ثبت کن و روندت را شروع کن.',textAlign:TextAlign.center)))
    else ...today.reversed.map((a)=>Card(child:ListTile(leading:CircleAvatar(child:Icon(a.done?Icons.check:Icons.circle_outlined)),title:Text(a.title),subtitle:Text('${a.category} • ${a.minutes} دقیقه'),trailing:IconButton(icon:const Icon(Icons.delete_outline),onPressed:(){setState(()=>items.remove(a));save();})))),
  ]);}
  Widget stat(String a,String b,IconData i)=>Card(child:ListTile(leading:Icon(i,size:32),title:Text(a),trailing:Text(b,style:const TextStyle(fontSize:18,fontWeight:FontWeight.bold))));
  Widget analysis(){final w=week,total=w.fold(0,(s,a)=>s+a.minutes);final avg=w.isEmpty?0:(total/w.length).round();final days=List.generate(7,(i)=>w.where((a)=>a.date.weekday==i+1).fold(0,(s,a)=>s+a.minutes));final best=days.isEmpty?0:days.reduce((a,b)=>a>b?a:b);return ListView(padding:const EdgeInsets.all(16),children:[
    Text('تحلیل هفتگی',style:Theme.of(context).textTheme.headlineSmall?.copyWith(fontWeight:FontWeight.bold)),const SizedBox(height:12),
    stat('تعداد فعالیت','$${w.length}'.replaceFirst('\$',''),Icons.task_alt),stat('زمان مفید','$total دقیقه',Icons.timer_outlined),stat('میانگین فعالیت','$avg دقیقه',Icons.insights),stat('بهترین روز','$best دقیقه',Icons.emoji_events_outlined),
    const SizedBox(height:16),Card(child:Padding(padding:const EdgeInsets.all(16),child:Column(crossAxisAlignment:CrossAxisAlignment.start,children:[const Text('روند هفته',style:TextStyle(fontSize:18,fontWeight:FontWeight.bold)),const SizedBox(height:14),Row(crossAxisAlignment:CrossAxisAlignment.end,mainAxisAlignment:MainAxisAlignment.spaceAround,children:List.generate(7,(i){final v=days[i];return Column(children:[Container(width:30,height:(v==0?4:(v/best*120)),decoration:BoxDecoration(color:Theme.of(context).colorScheme.primary,borderRadius:BorderRadius.circular(8))),const SizedBox(height:5),Text(['ش','د','س','چ','پ','ج','ش'][i]),Text('$v',style:const TextStyle(fontSize:11))]);}))]))),
    const SizedBox(height:12),Card(child:Padding(padding:const EdgeInsets.all(16),child:Text(w.isEmpty?'هنوز داده‌ای برای تحلیل وجود ندارد.':total>=300?'عملکردت این هفته خوب بوده؛ همین روند را حفظ کن.':'این هفته فعالیت ثبت شده، ولی هنوز جای رشد داری. هدف هفته بعد را کمی بالاتر بگذار.',style:const TextStyle(fontSize:16))))
  ]);}
  Widget calendar(){final now=DateTime.now();final days=List.generate(30,(i)=>now.subtract(Duration(days:29-i)));return ListView(padding:const EdgeInsets.all(16),children:[Text('۳۰ روز اخیر',style:Theme.of(context).textTheme.headlineSmall?.copyWith(fontWeight:FontWeight.bold)),const SizedBox(height:12),...days.reversed.map((d){final x=items.where((a)=>DateUtils.isSameDay(a.date,d)).toList();return ListTile(leading:CircleAvatar(child:Text('${d.day}')),title:Text('${d.day}/${d.month}/${d.year}'),subtitle:Text(x.isEmpty?'بدون فعالیت':'${x.length} فعالیت • ${x.fold(0,(s,a)=>s+a.minutes)} دقیقه'));})]);}
}
